-module(bmscl_shard_handoff_store).

%% Trusted codec and durable-store adapter for shard handoff control-plane state.
%% The persisted format is JSON and intentionally excludes PIDs, refs, node
%% terms, and other VM-local values.

-export([
    load/1,
    commit/2,
    cutover/2,
    current_owner_epoch/1,
    encode/1,
    decode/1
]).

-define(STORE_SCHEMA, <<"bmscl.shard-handoff.store/v1">>).
-define(HANDOFF_SCHEMA, <<"bmscl.shard-handoff/v1">>).

-spec load(map()) -> {ok, not_found} | {ok, map()} | {error, term()}.
load(Scope) ->
    Store = store_module(),
    case safe_call(Store, load_handoff, [Scope]) of
        {ok, not_found} ->
            {ok, not_found};
        {ok, #{version := Version, state := Payload}}
          when is_integer(Version), Version >= 0, is_binary(Payload) ->
            case decode(Payload) of
                {ok, State} ->
                    {ok, #{version => Version, state => State}};
                {error, Reason} ->
                    {error, {handoff_state_decode_failed, Reason}}
            end;
        {error, Reason} ->
            {error, {handoff_state_load_failed, Reason}};
        Other ->
            {error, {invalid_handoff_load_result, Other}}
    end.

-spec commit(non_neg_integer(), map()) -> {ok, map()} | {error, term()}.
commit(ExpectedVersion, State)
  when is_integer(ExpectedVersion), ExpectedVersion >= 0, is_map(State) ->
    Scope = maps:get(shard_scope, State, undefined),
    Payload = encode(State),
    Store = store_module(),
    case safe_call(Store, commit_handoff, [Scope, ExpectedVersion, Payload]) of
        {ok, NextVersion} when is_integer(NextVersion), NextVersion =:= ExpectedVersion + 1 ->
            {ok, #{version => NextVersion, state => State}};
        {ok, NextVersion} ->
            {error, {invalid_handoff_next_version, NextVersion}};
        {error, Reason} ->
            {error, Reason};
        Other ->
            {error, {invalid_handoff_commit_result, Other}}
    end;
commit(_, _) ->
    {error, invalid_handoff_commit}.

-spec cutover(non_neg_integer(), map()) -> {ok, map()} | {error, term()}.
cutover(ExpectedVersion, State)
  when is_integer(ExpectedVersion), ExpectedVersion >= 0, is_map(State) ->
    Scope = maps:get(shard_scope, State, undefined),
    Payload = encode(State),
    Store = store_module(),
    case safe_call(Store, cutover_handoff, [Scope, ExpectedVersion, Payload]) of
        {ok, #{version := NextVersion, owner_epoch := OwnerEpoch}}
          when is_integer(NextVersion), NextVersion =:= ExpectedVersion + 1,
               is_integer(OwnerEpoch), OwnerEpoch > 0 ->
            {ok, #{version => NextVersion,
                   owner_epoch => OwnerEpoch,
                   state => State}};
        {ok, Other} ->
            {error, {invalid_handoff_cutover_result, Other}};
        {error, Reason} ->
            {error, Reason};
        Other ->
            {error, {invalid_handoff_cutover_result, Other}}
    end;
cutover(_, _) ->
    {error, invalid_handoff_cutover}.

-spec current_owner_epoch(map()) -> {ok, not_found | pos_integer()} | {error, term()}.
current_owner_epoch(Scope) ->
    Store = store_module(),
    case safe_call(Store, current_owner_epoch, [Scope]) of
        {ok, not_found} ->
            {ok, not_found};
        {ok, Epoch} when is_integer(Epoch), Epoch > 0 ->
            {ok, Epoch};
        {error, Reason} ->
            {error, Reason};
        Other ->
            {error, {invalid_owner_epoch_result, Other}}
    end.

-spec encode(map()) -> binary().
encode(State) ->
    Scope = maps:get(shard_scope, State),
    Progress = maps:get(progress, State),
    jsx:encode(#{
        <<"schema">> => ?STORE_SCHEMA,
        <<"handoff_schema">> => maps:get(schema, State, ?HANDOFF_SCHEMA),
        <<"shard_scope">> => encode_scope(Scope),
        <<"phase">> => phase_binary(maps:get(phase, State)),
        <<"placement_epoch">> => maps:get(placement_epoch, State),
        <<"barrier_position">> => maps:get(barrier_position, State),
        <<"old_replicas">> => maps:get(old_replicas, State),
        <<"target_replicas">> => maps:get(target_replicas, State),
        <<"retired_replicas">> => maps:get(retired_replicas, State, []),
        <<"progress">> => Progress
    }).

-spec decode(binary()) -> {ok, map()} | {error, term()}.
decode(Payload) when is_binary(Payload) ->
    try jsx:decode(Payload, [return_maps]) of
        #{<<"schema">> := ?STORE_SCHEMA,
          <<"handoff_schema">> := ?HANDOFF_SCHEMA,
          <<"shard_scope">> := Scope0,
          <<"phase">> := Phase0,
          <<"placement_epoch">> := PlacementEpoch,
          <<"barrier_position">> := BarrierPosition,
          <<"old_replicas">> := OldReplicas0,
          <<"target_replicas">> := TargetReplicas0,
          <<"retired_replicas">> := RetiredReplicas0,
          <<"progress">> := Progress0}
          when is_integer(PlacementEpoch), PlacementEpoch > 0,
               is_integer(BarrierPosition), BarrierPosition >= 0,
               is_map(Progress0) ->
            decode_state(
              Scope0,
              Phase0,
              PlacementEpoch,
              BarrierPosition,
              OldReplicas0,
              TargetReplicas0,
              RetiredReplicas0,
              Progress0);
        _ ->
            {error, invalid_handoff_payload}
    catch
        _:_ ->
            {error, invalid_handoff_json}
    end;
decode(_) ->
    {error, invalid_handoff_payload}.

decode_state(Scope0, Phase0, PlacementEpoch, BarrierPosition,
             OldReplicas0, TargetReplicas0, RetiredReplicas0, Progress0) ->
    case {decode_scope(Scope0),
          decode_phase(Phase0),
          decode_replica_list(OldReplicas0),
          decode_replica_list(TargetReplicas0),
          decode_replica_list(RetiredReplicas0),
          decode_progress(Progress0)} of
        {{ok, Scope}, {ok, Phase}, {ok, OldReplicas}, {ok, TargetReplicas},
         {ok, RetiredReplicas}, {ok, Progress}} ->
            Learners = subtract(TargetReplicas, OldReplicas),
            Retiring = subtract(OldReplicas, TargetReplicas),
            case valid_reconstructed_state(
                   Phase,
                   TargetReplicas,
                   Retiring,
                   RetiredReplicas,
                   Progress) of
                true ->
                    Base = #{
                        schema => ?HANDOFF_SCHEMA,
                        shard_scope => Scope,
                        phase => Phase,
                        placement_epoch => PlacementEpoch,
                        barrier_position => BarrierPosition,
                        old_replicas => OldReplicas,
                        target_replicas => TargetReplicas,
                        learners => Learners,
                        retiring_replicas => Retiring,
                        retired_replicas => RetiredReplicas,
                        progress => Progress
                    },
                    {ok, restore_cutover_fields(Base)};
                false ->
                    {error, inconsistent_handoff_state}
            end;
        _ ->
            {error, invalid_handoff_state}
    end.

restore_cutover_fields(State = #{phase := catching_up}) ->
    State;
restore_cutover_fields(State = #{target_replicas := TargetReplicas,
                                 retiring_replicas := Retiring}) ->
    State#{active_replicas => TargetReplicas,
           fenced_replicas => Retiring}.

valid_reconstructed_state(Phase, TargetReplicas, Retiring, RetiredReplicas, Progress) ->
    TargetReplicas =/= []
    andalso lists:all(fun(Replica) -> maps:is_key(Replica, Progress) end, TargetReplicas)
    andalso lists:all(fun(Replica) -> lists:member(Replica, Retiring) end, RetiredReplicas)
    andalso case Phase of
        catching_up ->
            RetiredReplicas =:= [];
        cutover ->
            length(RetiredReplicas) < length(Retiring);
        complete ->
            lists:sort(RetiredReplicas) =:= lists:sort(Retiring)
            orelse Retiring =:= []
    end.

encode_scope(#{tenant_id := Tenant,
               application_id := Application,
               namespace := Namespace,
               virtual_shard := VirtualShard}) ->
    #{<<"tenant_id">> => Tenant,
      <<"application_id">> => Application,
      <<"namespace">> => Namespace,
      <<"virtual_shard">> => VirtualShard}.

decode_scope(#{<<"tenant_id">> := Tenant,
               <<"application_id">> := Application,
               <<"namespace">> := Namespace,
               <<"virtual_shard">> := VirtualShard})
  when is_integer(VirtualShard), VirtualShard >= 0 ->
    case {bounded_binary(Tenant, 1024),
          bounded_binary(Application, 1024),
          bounded_binary(Namespace, 128)} of
        {true, true, true} ->
            {ok, #{tenant_id => Tenant,
                   application_id => Application,
                   namespace => Namespace,
                   virtual_shard => VirtualShard}};
        _ ->
            {error, invalid_scope}
    end;
decode_scope(_) ->
    {error, invalid_scope}.

phase_binary(catching_up) ->
    <<"catching_up">>;
phase_binary(cutover) ->
    <<"cutover">>;
phase_binary(complete) ->
    <<"complete">>.

decode_phase(<<"catching_up">>) ->
    {ok, catching_up};
decode_phase(<<"cutover">>) ->
    {ok, cutover};
decode_phase(<<"complete">>) ->
    {ok, complete};
decode_phase(_) ->
    {error, invalid_phase}.

decode_replica_list(Value) when is_list(Value) ->
    case lists:all(fun valid_replica/1, Value) of
        true ->
            Unique = lists:usort(Value),
            case length(Unique) =:= length(Value) of
                true ->
                    {ok, Unique};
                false ->
                    {error, duplicate_replica}
            end;
        false ->
            {error, invalid_replica}
    end;
decode_replica_list(_) ->
    {error, invalid_replica_list}.

decode_progress(Progress) when is_map(Progress) ->
    Pairs = maps:to_list(Progress),
    case lists:all(
           fun({Replica, Position}) ->
               valid_replica(Replica)
               andalso is_integer(Position)
               andalso Position >= 0
           end,
           Pairs) of
        true ->
            {ok, Progress};
        false ->
            {error, invalid_progress}
    end.

valid_replica(Value) ->
    bounded_binary(Value, 256).

bounded_binary(Value, Max) ->
    is_binary(Value)
    andalso byte_size(Value) > 0
    andalso byte_size(Value) =< Max
    andalso binary:match(Value, <<0>>) =:= nomatch.

subtract(Left, Right) ->
    [Value || Value <- Left, not lists:member(Value, Right)].

store_module() ->
    application:get_env(
      bmscl_supervisor,
      durable_store_module,
      bmscl_durable_store_redis).

safe_call(Module, Function, Args) ->
    try apply(Module, Function, Args) of
        Result ->
            Result
    catch
        Class:Reason ->
            {error, {store_exception, Class, Reason}}
    end.

-ifdef(TEST).
-include_lib("eunit/include/eunit.hrl").

codec_round_trip_catching_up_test() ->
    {ok, State0} = bmscl_shard_handoff:begin_handoff(
        #{tenant_id => <<"tenant-a">>,
          application_id => <<"app-a">>,
          namespace => <<"orders">>,
          virtual_shard => 17},
        8,
        [<<"a">>, <<"b">>, <<"c">>],
        [<<"b">>, <<"c">>, <<"d">>],
        100),
    {ok, State1} = bmscl_shard_handoff:record_progress(State0, <<"d">>, 44),
    ?assertEqual({ok, State1}, decode(encode(State1))).

codec_round_trip_cutover_test() ->
    {ok, State0} = bmscl_shard_handoff:begin_handoff(
        #{tenant_id => <<"tenant-a">>,
          application_id => <<"app-a">>,
          namespace => <<"orders">>,
          virtual_shard => 17},
        8,
        [<<"a">>, <<"b">>, <<"c">>],
        [<<"b">>, <<"c">>, <<"d">>],
        100),
    {ok, State1} = bmscl_shard_handoff:record_progress(State0, <<"d">>, 100),
    {ok, State2} = bmscl_shard_handoff:advance_placement_epoch(State1, 9),
    ?assertEqual({ok, State2}, decode(encode(State2))).

-endif.
