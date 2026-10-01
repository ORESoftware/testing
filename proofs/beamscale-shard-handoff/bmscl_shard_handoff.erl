-module(bmscl_shard_handoff).

-export([
    begin_handoff/5,
    record_progress/3,
    can_cutover/1,
    advance_placement_epoch/2,
    mark_retired/2,
    status/1
]).

-define(SCHEMA, <<"bmscl.shard-handoff/v1">>).

-type replica_id() :: binary().
-type position() :: non_neg_integer().
-type phase() :: catching_up | cutover | complete.
-type state() :: map().

-export_type([state/0, replica_id/0, position/0, phase/0]).

-spec begin_handoff(map(), pos_integer(), [replica_id()], [replica_id()], position()) ->
    {ok, state()} | {error, term()}.
begin_handoff(ShardScope, PlacementEpoch, OldReplicas0, TargetReplicas0, BarrierPosition)
  when is_map(ShardScope),
       is_integer(PlacementEpoch), PlacementEpoch > 0,
       is_integer(BarrierPosition), BarrierPosition >= 0,
       is_list(OldReplicas0), is_list(TargetReplicas0) ->
    case {normalize_replicas(OldReplicas0), normalize_replicas(TargetReplicas0)} of
        {{ok, []}, _} ->
            {error, empty_old_replica_set};
        {_, {ok, []}} ->
            {error, empty_target_replica_set};
        {{ok, OldReplicas}, {ok, TargetReplicas}} ->
            Learners = subtract(TargetReplicas, OldReplicas),
            Retiring = subtract(OldReplicas, TargetReplicas),
            Progress = initial_progress(TargetReplicas, OldReplicas, BarrierPosition),
            {ok, #{
                schema => ?SCHEMA,
                shard_scope => ShardScope,
                phase => catching_up,
                placement_epoch => PlacementEpoch,
                barrier_position => BarrierPosition,
                old_replicas => OldReplicas,
                target_replicas => TargetReplicas,
                learners => Learners,
                retiring_replicas => Retiring,
                retired_replicas => [],
                progress => Progress
            }};
        {{error, Reason}, _} ->
            {error, Reason};
        {_, {error, Reason}} ->
            {error, Reason}
    end;
begin_handoff(_, _, _, _, _) ->
    {error, invalid_handoff}.

-spec record_progress(state(), replica_id(), position()) ->
    {ok, state()} | {error, term()}.
record_progress(State = #{phase := catching_up,
                          target_replicas := TargetReplicas,
                          progress := Progress},
                Replica,
                Position)
  when is_binary(Replica), byte_size(Replica) > 0,
       is_integer(Position), Position >= 0 ->
    case lists:member(Replica, TargetReplicas) of
        false ->
            {error, unknown_target_replica};
        true ->
            Current = maps:get(Replica, Progress, 0),
            case Position >= Current of
                false ->
                    {error, {stale_replica_progress, Current}};
                true ->
                    {ok, State#{progress => Progress#{Replica => Position}}}
            end
    end;
record_progress(#{phase := Phase}, _, _) when Phase =/= catching_up ->
    {error, handoff_not_catching_up};
record_progress(_, _, _) ->
    {error, invalid_replica_progress}.

-spec can_cutover(state()) -> boolean().
can_cutover(#{phase := catching_up,
              target_replicas := TargetReplicas,
              barrier_position := BarrierPosition,
              progress := Progress}) ->
    lists:all(
      fun(Replica) ->
          maps:get(Replica, Progress, 0) >= BarrierPosition
      end,
      TargetReplicas);
can_cutover(_) ->
    false.

-spec advance_placement_epoch(state(), pos_integer()) ->
    {ok, state()} | {error, term()}.
advance_placement_epoch(State = #{phase := catching_up,
                                  placement_epoch := PlacementEpoch,
                                  target_replicas := TargetReplicas,
                                  retiring_replicas := Retiring},
                        NextEpoch)
  when is_integer(NextEpoch), NextEpoch > PlacementEpoch ->
    case can_cutover(State) of
        false ->
            {error, target_not_caught_up};
        true ->
            NextPhase = case Retiring of
                [] -> complete;
                _ -> cutover
            end,
            {ok, State#{
                phase => NextPhase,
                placement_epoch => NextEpoch,
                active_replicas => TargetReplicas,
                fenced_replicas => Retiring
            }}
    end;
advance_placement_epoch(#{placement_epoch := PlacementEpoch}, NextEpoch)
  when is_integer(NextEpoch), NextEpoch =< PlacementEpoch ->
    {error, {non_monotonic_placement_epoch, PlacementEpoch}};
advance_placement_epoch(#{phase := Phase}, _) when Phase =/= catching_up ->
    {error, handoff_already_cut_over};
advance_placement_epoch(_, _) ->
    {error, invalid_placement_epoch}.

-spec mark_retired(state(), replica_id()) -> {ok, state()} | {error, term()}.
mark_retired(State = #{phase := cutover,
                       retiring_replicas := Retiring,
                       retired_replicas := Retired0},
             Replica)
  when is_binary(Replica), byte_size(Replica) > 0 ->
    case lists:member(Replica, Retiring) of
        false ->
            {error, replica_not_retiring};
        true ->
            Retired = lists:usort([Replica | Retired0]),
            Phase = case lists:sort(Retired) =:= lists:sort(Retiring) of
                true -> complete;
                false -> cutover
            end,
            {ok, State#{retired_replicas => Retired, phase => Phase}}
    end;
mark_retired(#{phase := complete} = State, Replica) when is_binary(Replica) ->
    Retired = maps:get(retired_replicas, State, []),
    case lists:member(Replica, Retired) of
        true -> {ok, State};
        false -> {error, handoff_complete}
    end;
mark_retired(_, _) ->
    {error, handoff_not_cut_over}.

-spec status(state()) -> map().
status(State = #{target_replicas := TargetReplicas,
                 barrier_position := BarrierPosition,
                 progress := Progress}) ->
    Lag = maps:from_list([
        {Replica, erlang:max(0, BarrierPosition - maps:get(Replica, Progress, 0))}
        || Replica <- TargetReplicas
    ]),
    State#{can_cutover => can_cutover(State), replica_lag => Lag}.

normalize_replicas(Replicas) ->
    case lists:all(fun valid_replica/1, Replicas) of
        false ->
            {error, invalid_replica_id};
        true ->
            {ok, lists:usort(Replicas)}
    end.

valid_replica(Value) when is_binary(Value),
                          byte_size(Value) > 0,
                          byte_size(Value) =< 256 ->
    binary:match(Value, <<0>>) =:= nomatch;
valid_replica(_) ->
    false.

subtract(Left, Right) ->
    [Value || Value <- Left, not lists:member(Value, Right)].

initial_progress(TargetReplicas, OldReplicas, BarrierPosition) ->
    maps:from_list([
        {Replica, case lists:member(Replica, OldReplicas) of
            true -> BarrierPosition;
            false -> 0
        end}
        || Replica <- TargetReplicas
    ]).

-ifdef(TEST).
-include_lib("eunit/include/eunit.hrl").

learner_must_catch_up_before_cutover_test() ->
    {ok, State0} = begin_handoff(
        #{virtual_shard => 17},
        71,
        [<<"a">>, <<"b">>, <<"c">>],
        [<<"b">>, <<"c">>, <<"d">>],
        10044),
    ?assertEqual(false, can_cutover(State0)),
    ?assertMatch({error, target_not_caught_up}, advance_placement_epoch(State0, 72)),
    {ok, State1} = record_progress(State0, <<"d">>, 10043),
    ?assertEqual(false, can_cutover(State1)),
    {ok, State2} = record_progress(State1, <<"d">>, 10044),
    ?assertEqual(true, can_cutover(State2)),
    {ok, State3} = advance_placement_epoch(State2, 72),
    ?assertEqual(cutover, maps:get(phase, State3)),
    ?assertEqual([<<"a">>], maps:get(fenced_replicas, State3)),
    {ok, State4} = mark_retired(State3, <<"a">>),
    ?assertEqual(complete, maps:get(phase, State4)).

replica_progress_is_monotonic_test() ->
    {ok, State0} = begin_handoff(
        #{virtual_shard => 9},
        4,
        [<<"a">>],
        [<<"a">>, <<"b">>],
        12),
    {ok, State1} = record_progress(State0, <<"b">>, 8),
    ?assertEqual(
       {error, {stale_replica_progress, 8}},
       record_progress(State1, <<"b">>, 7)).

placement_epoch_must_advance_test() ->
    {ok, State0} = begin_handoff(
        #{virtual_shard => 5},
        10,
        [<<"a">>],
        [<<"a">>],
        99),
    ?assertEqual(true, can_cutover(State0)),
    ?assertEqual(
       {error, {non_monotonic_placement_epoch, 10}},
       advance_placement_epoch(State0, 10)),
    {ok, State1} = advance_placement_epoch(State0, 11),
    ?assertEqual(complete, maps:get(phase, State1)).

status_reports_replica_lag_test() ->
    {ok, State0} = begin_handoff(
        #{virtual_shard => 21},
        1,
        [<<"a">>],
        [<<"a">>, <<"b">>],
        50),
    {ok, State1} = record_progress(State0, <<"b">>, 47),
    Status = status(State1),
    ?assertEqual(0, maps:get(<<"a">>, maps:get(replica_lag, Status))),
    ?assertEqual(3, maps:get(<<"b">>, maps:get(replica_lag, Status))).

-endif.
