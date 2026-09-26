-module(bmscl_queue_quorum_log).

-export([
    new/6,
    append/6,
    record_replica_durable/4,
    begin_term/4,
    entry/2,
    replay/3,
    append_status/2,
    migration_barrier/1,
    status/1
]).

-define(MAX_SAFE_INTEGER, 9007199254740991).
-define(MAX_REPLICA_ID_BYTES, 256).
-define(MAX_PARTITION_ID_BYTES, 1024).

-type state() :: map().
-type replica_id() :: binary().
-type sequence() :: non_neg_integer().
-type queue_term() :: pos_integer().

-export_type([state/0, replica_id/0, sequence/0, queue_term/0]).

-spec new(binary(), queue_term(), replica_id(), [replica_id()], pos_integer(), pos_integer()) ->
    {ok, state()} | {error, term()}.
new(PartitionId, Term, Leader, Replicas0, Quorum, MaxUncommitted)
  when is_binary(PartitionId),
       byte_size(PartitionId) > 0,
       byte_size(PartitionId) =< ?MAX_PARTITION_ID_BYTES,
       is_integer(Term), Term > 0, Term =< ?MAX_SAFE_INTEGER,
       is_binary(Leader),
       is_list(Replicas0),
       is_integer(Quorum), Quorum > 0,
       is_integer(MaxUncommitted), MaxUncommitted > 0 ->
    case normalize_replicas(Replicas0) of
        {ok, Replicas} when Replicas =/= [] ->
            case {lists:member(Leader, Replicas), Quorum =< length(Replicas)} of
                {false, _} ->
                    {error, leader_not_replica};
                {_, false} ->
                    {error, quorum_exceeds_replica_count};
                {true, true} ->
                    Progress = maps:from_list([{Replica, 0} || Replica <- Replicas]),
                    {ok, #{
                        partition_id => PartitionId,
                        term => Term,
                        leader => Leader,
                        replicas => Replicas,
                        quorum => Quorum,
                        max_uncommitted => MaxUncommitted,
                        next_sequence => 1,
                        last_sequence => 0,
                        committed_sequence => 0,
                        entries => #{},
                        idempotency => #{},
                        durable_progress => Progress
                    }}
            end;
        {ok, []} ->
            {error, empty_replica_set};
        {error, Reason} ->
            {error, Reason}
    end;
new(_, _, _, _, _, _) ->
    {error, invalid_queue_log_config}.

-spec append(state(), queue_term(), replica_id(), non_neg_integer(), binary(), binary()) ->
    {ok, map(), state()} | {error, term()}.
append(State, CallerTerm, CallerReplica, TimestampMs, IdempotencyKey, Payload)
  when is_map(State),
       is_integer(CallerTerm), CallerTerm > 0,
       is_binary(CallerReplica),
       is_integer(TimestampMs), TimestampMs >= 0,
       is_binary(IdempotencyKey),
       is_binary(Payload) ->
    CurrentTerm = maps:get(term, State),
    Leader = maps:get(leader, State),
    case {CallerTerm =:= CurrentTerm, CallerReplica =:= Leader} of
        {false, _} ->
            {error, {stale_or_future_term, CurrentTerm}};
        {_, false} ->
            {error, {not_partition_leader, Leader}};
        {true, true} ->
            append_as_leader(State, TimestampMs, IdempotencyKey, Payload)
    end;
append(_, _, _, _, _, _) ->
    {error, invalid_queue_append}.

-spec record_replica_durable(state(), replica_id(), queue_term(), sequence()) ->
    {ok, state()} | {error, term()}.
record_replica_durable(State, Replica, CallerTerm, MatchSequence)
  when is_map(State),
       is_binary(Replica),
       is_integer(CallerTerm), CallerTerm > 0,
       is_integer(MatchSequence), MatchSequence >= 0 ->
    CurrentTerm = maps:get(term, State),
    Replicas = maps:get(replicas, State),
    LastSequence = maps:get(last_sequence, State),
    Progress0 = maps:get(durable_progress, State),
    case CallerTerm =:= CurrentTerm of
        false ->
            {error, {stale_or_future_term, CurrentTerm}};
        true ->
            case lists:member(Replica, Replicas) of
                false ->
                    {error, unknown_replica};
                true when MatchSequence > LastSequence ->
                    {error, {replica_ahead_of_leader, LastSequence}};
                true ->
                    Current = maps:get(Replica, Progress0, 0),
                    case MatchSequence >= Current of
                        false ->
                            {error, {stale_replica_progress, Current}};
                        true ->
                            Progress1 = Progress0#{Replica => MatchSequence},
                            {ok, advance_commit(State#{durable_progress => Progress1})}
                    end
            end
    end;
record_replica_durable(_, _, _, _) ->
    {error, invalid_replica_progress}.

-spec begin_term(state(), queue_term(), replica_id(), sequence()) ->
    {ok, state()} | {error, term()}.
begin_term(State, NextTerm, NextLeader, RetainedLastSequence)
  when is_map(State),
       is_integer(NextTerm), NextTerm > 0, NextTerm =< ?MAX_SAFE_INTEGER,
       is_binary(NextLeader),
       is_integer(RetainedLastSequence), RetainedLastSequence >= 0 ->
    CurrentTerm = maps:get(term, State),
    Committed = maps:get(committed_sequence, State),
    Last = maps:get(last_sequence, State),
    Replicas = maps:get(replicas, State),
    case {
        NextTerm > CurrentTerm,
        lists:member(NextLeader, Replicas),
        RetainedLastSequence >= Committed,
        RetainedLastSequence =< Last
    } of
        {false, _, _, _} ->
            {error, {non_monotonic_term, CurrentTerm}};
        {_, false, _, _} ->
            {error, leader_not_replica};
        {_, _, false, _} ->
            {error, {cannot_truncate_committed_prefix, Committed}};
        {_, _, _, false} ->
            {error, {retained_index_beyond_tail, Last}};
        {true, true, true, true} ->
            Entries0 = maps:get(entries, State),
            Entries1 = maps:filter(
                         fun(Index, _Entry) -> Index =< RetainedLastSequence end,
                         Entries0),
            Progress0 = maps:get(durable_progress, State),
            Progress1 = maps:map(
                         fun(_Replica, Index) -> erlang:min(Index, RetainedLastSequence) end,
                         Progress0),
            Progress2 = Progress1#{NextLeader => RetainedLastSequence},
            {ok, State#{
                term => NextTerm,
                leader => NextLeader,
                last_sequence => RetainedLastSequence,
                next_sequence => RetainedLastSequence + 1,
                entries => Entries1,
                idempotency => rebuild_idempotency(Entries1),
                durable_progress => Progress2
            }}
    end;
begin_term(_, _, _, _) ->
    {error, invalid_term_transition}.

-spec entry(state(), pos_integer()) -> {ok, map()} | {error, term()}.
entry(State, Sequence) when is_map(State), is_integer(Sequence), Sequence > 0 ->
    case maps:find(Sequence, maps:get(entries, State)) of
        {ok, Entry} ->
            {ok, Entry};
        error ->
            {error, not_found}
    end;
entry(_, _) ->
    {error, invalid_sequence}.

-spec replay(state(), pos_integer(), pos_integer()) -> {ok, [map()]} | {error, term()}.
replay(State, FromSequence, Limit)
  when is_map(State),
       is_integer(FromSequence), FromSequence > 0,
       is_integer(Limit), Limit > 0 ->
    Committed = maps:get(committed_sequence, State),
    Entries = maps:get(entries, State),
    LastWanted = erlang:min(Committed, FromSequence + Limit - 1),
    case FromSequence =< Committed of
        false ->
            {ok, []};
        true ->
            {ok, [maps:get(Index, Entries)
                  || Index <- lists:seq(FromSequence, LastWanted)]}
    end;
replay(_, _, _) ->
    {error, invalid_replay_range}.

-spec append_status(state(), pos_integer()) ->
    {ok, pending | committed} | {error, term()}.
append_status(State, Sequence) when is_map(State), is_integer(Sequence), Sequence > 0 ->
    Last = maps:get(last_sequence, State),
    Committed = maps:get(committed_sequence, State),
    case Sequence =< Last of
        false ->
            {error, not_found};
        true when Sequence =< Committed ->
            {ok, committed};
        true ->
            {ok, pending}
    end;
append_status(_, _) ->
    {error, invalid_sequence}.

-spec migration_barrier(state()) -> sequence().
migration_barrier(State) when is_map(State) ->
    maps:get(committed_sequence, State).

-spec status(state()) -> map().
status(State) when is_map(State) ->
    Committed = maps:get(committed_sequence, State),
    Last = maps:get(last_sequence, State),
    Progress = maps:get(durable_progress, State),
    State#{
        uncommitted_count => Last - Committed,
        replica_lag => maps:map(
            fun(_Replica, MatchSequence) -> Last - MatchSequence end,
            Progress),
        migration_barrier => Committed
    }.

append_as_leader(State, TimestampMs, IdempotencyKey, Payload) ->
    case existing_idempotency(State, IdempotencyKey) of
        {existing, Sequence} ->
            Status = case Sequence =< maps:get(committed_sequence, State) of
                true -> committed;
                false -> pending
            end,
            {ok, #{sequence => Sequence, status => Status, duplicate => true}, State};
        none ->
            Committed = maps:get(committed_sequence, State),
            Last = maps:get(last_sequence, State),
            MaxUncommitted = maps:get(max_uncommitted, State),
            case Last - Committed >= MaxUncommitted of
                true ->
                    {error, queue_replication_backpressure};
                false ->
                    append_new_record(State, TimestampMs, IdempotencyKey, Payload)
            end
    end.

append_new_record(State, TimestampMs, IdempotencyKey, Payload) ->
    Term = maps:get(term, State),
    Sequence = maps:get(next_sequence, State),
    case bmscl_queue_frame:encode(
           Term,
           Sequence,
           TimestampMs,
           IdempotencyKey,
           Payload) of
        {ok, Frame} ->
            Entry = #{
                term => Term,
                sequence => Sequence,
                frame => Frame,
                checksum => bmscl_queue_frame:checksum(strip_checksum(Frame)),
                idempotency_key => IdempotencyKey
            },
            Entries1 = (maps:get(entries, State))#{Sequence => Entry},
            Leader = maps:get(leader, State),
            Progress1 = (maps:get(durable_progress, State))#{Leader => Sequence},
            Idempotency1 = put_idempotency(
                             maps:get(idempotency, State),
                             IdempotencyKey,
                             Sequence),
            State1 = State#{
                next_sequence => Sequence + 1,
                last_sequence => Sequence,
                entries => Entries1,
                idempotency => Idempotency1,
                durable_progress => Progress1
            },
            State2 = advance_commit(State1),
            Status = case Sequence =< maps:get(committed_sequence, State2) of
                true -> committed;
                false -> pending
            end,
            {ok, #{sequence => Sequence, status => Status, duplicate => false}, State2};
        {error, Reason} ->
            {error, Reason}
    end.

strip_checksum(Frame) ->
    BodySize = byte_size(Frame) - bmscl_queue_frame:checksum_size(),
    <<Body:BodySize/binary, _/binary>> = Frame,
    Body.

advance_commit(State) ->
    Quorum = maps:get(quorum, State),
    Progress = maps:values(maps:get(durable_progress, State)),
    Descending = lists:reverse(lists:sort(Progress)),
    Candidate = lists:nth(Quorum, Descending),
    Committed = maps:get(committed_sequence, State),
    CurrentTerm = maps:get(term, State),
    Entries = maps:get(entries, State),
    NextCommitted = highest_current_term_index(
                      Candidate,
                      Committed,
                      CurrentTerm,
                      Entries),
    State#{committed_sequence => NextCommitted}.

highest_current_term_index(Candidate, Committed, _CurrentTerm, _Entries)
  when Candidate =< Committed ->
    Committed;
highest_current_term_index(Candidate, Committed, CurrentTerm, Entries) ->
    case maps:find(Candidate, Entries) of
        {ok, #{term := CurrentTerm}} ->
            Candidate;
        _ ->
            highest_current_term_index(
              Candidate - 1,
              Committed,
              CurrentTerm,
              Entries)
    end.

existing_idempotency(_State, <<>>) ->
    none;
existing_idempotency(State, Key) ->
    case maps:find(Key, maps:get(idempotency, State)) of
        {ok, Sequence} ->
            {existing, Sequence};
        error ->
            none
    end.

put_idempotency(Map, <<>>, _Sequence) ->
    Map;
put_idempotency(Map, Key, Sequence) ->
    Map#{Key => Sequence}.

rebuild_idempotency(Entries) ->
    maps:fold(
      fun(Sequence, Entry, Acc) ->
          put_idempotency(
            Acc,
            maps:get(idempotency_key, Entry, <<>>),
            Sequence)
      end,
      #{},
      Entries).

normalize_replicas(Replicas) ->
    case lists:all(fun valid_replica/1, Replicas) of
        false ->
            {error, invalid_replica_id};
        true ->
            Unique = lists:usort(Replicas),
            case length(Unique) =:= length(Replicas) of
                true ->
                    {ok, Unique};
                false ->
                    {error, duplicate_replica_id}
            end
    end.

valid_replica(Value) when is_binary(Value),
                          byte_size(Value) > 0,
                          byte_size(Value) =< ?MAX_REPLICA_ID_BYTES ->
    binary:match(Value, <<0>>) =:= nomatch;
valid_replica(_) ->
    false.

-ifdef(TEST).
-include_lib("eunit/include/eunit.hrl").

producer_ack_waits_for_quorum_test() ->
    {ok, S0} = test_log(2),
    {ok, #{sequence := 1, status := pending}, S1} = append(
        S0, 7, <<"a">>, 1000, <<"req-1">>, <<"hello">>),
    ?assertEqual(0, migration_barrier(S1)),
    {ok, S2} = record_replica_durable(S1, <<"b">>, 7, 1),
    ?assertEqual({ok, committed}, append_status(S2, 1)),
    ?assertEqual(1, migration_barrier(S2)).

leader_dies_before_quorum_uncommitted_suffix_is_removed_test() ->
    {ok, S0} = test_log(2),
    {ok, #{sequence := 1, status := pending}, S1} = append(
        S0, 7, <<"a">>, 1000, <<"req-1">>, <<"lost-if-uncommitted">>),
    {ok, S2} = begin_term(S1, 8, <<"b">>, 0),
    ?assertEqual(0, maps:get(last_sequence, S2)),
    ?assertEqual({error, not_found}, entry(S2, 1)),
    {ok, #{sequence := 1, duplicate := false}, _S3} = append(
        S2, 8, <<"b">>, 1001, <<"req-1">>, <<"retry">>).

leader_dies_after_commit_idempotency_survives_test() ->
    {ok, S0} = test_log(2),
    {ok, #{sequence := 1}, S1} = append(
        S0, 7, <<"a">>, 1000, <<"req-1">>, <<"hello">>),
    {ok, S2} = record_replica_durable(S1, <<"b">>, 7, 1),
    {ok, S3} = begin_term(S2, 8, <<"b">>, 1),
    {ok, #{sequence := 1, status := committed, duplicate := true}, S4} = append(
        S3, 8, <<"b">>, 1002, <<"req-1">>, <<"ignored-retry-body">>),
    ?assertEqual(1, maps:get(last_sequence, S4)).

stale_leader_cannot_append_test() ->
    {ok, S0} = test_log(2),
    {ok, S1} = begin_term(S0, 8, <<"b">>, 0),
    ?assertEqual(
       {error, {stale_or_future_term, 8}},
       append(S1, 7, <<"a">>, 1000, <<>>, <<"stale">>)),
    ?assertEqual(
       {error, {not_partition_leader, <<"b">>}},
       append(S1, 8, <<"a">>, 1000, <<>>, <<"stale">>)).

committed_prefix_cannot_be_truncated_test() ->
    {ok, S0} = test_log(2),
    {ok, _Result, S1} = append(S0, 7, <<"a">>, 1000, <<>>, <<"one">>),
    {ok, S2} = record_replica_durable(S1, <<"b">>, 7, 1),
    ?assertEqual(
       {error, {cannot_truncate_committed_prefix, 1}},
       begin_term(S2, 8, <<"b">>, 0)).

replay_exposes_only_committed_prefix_test() ->
    {ok, S0} = test_log(2),
    {ok, _, S1} = append(S0, 7, <<"a">>, 1000, <<>>, <<"one">>),
    {ok, S2} = record_replica_durable(S1, <<"b">>, 7, 1),
    {ok, _, S3} = append(S2, 7, <<"a">>, 1001, <<>>, <<"two">>),
    {ok, Replay} = replay(S3, 1, 10),
    ?assertEqual(1, length(Replay)),
    [Entry1] = Replay,
    ?assertEqual(1, maps:get(sequence, Entry1)).

backpressure_bounds_uncommitted_tail_test() ->
    {ok, S0} = new(<<"p-1">>, 7, <<"a">>, [<<"a">>, <<"b">>, <<"c">>], 2, 2),
    {ok, _, S1} = append(S0, 7, <<"a">>, 1, <<>>, <<"one">>),
    {ok, _, S2} = append(S1, 7, <<"a">>, 2, <<>>, <<"two">>),
    ?assertEqual(
       {error, queue_replication_backpressure},
       append(S2, 7, <<"a">>, 3, <<>>, <<"three">>)).

quorum_one_commits_on_local_durable_append_test() ->
    {ok, S0} = new(<<"p-1">>, 7, <<"a">>, [<<"a">>], 1, 8),
    {ok, #{sequence := 1, status := committed}, S1} = append(
        S0, 7, <<"a">>, 1, <<>>, <<"one">>),
    ?assertEqual(1, migration_barrier(S1)).

test_log(Quorum) ->
    new(
      <<"orders:0">>,
      7,
      <<"a">>,
      [<<"a">>, <<"b">>, <<"c">>],
      Quorum,
      1024).

-endif.
