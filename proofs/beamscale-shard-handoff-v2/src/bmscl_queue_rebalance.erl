-module(bmscl_queue_rebalance).

-export([
    begin_handoff/4,
    record_target_progress/3,
    cutover/2,
    mark_retired/2
]).

-spec begin_handoff(map(), map(), pos_integer(), [binary()]) ->
    {ok, map()} | {error, term()}.
begin_handoff(LogState, ShardScope, PlacementEpoch, TargetReplicas)
  when is_map(LogState), is_map(ShardScope),
       is_integer(PlacementEpoch), PlacementEpoch > 0,
       is_list(TargetReplicas) ->
    OldReplicas = maps:get(replicas, LogState),
    Barrier = bmscl_queue_quorum_log:migration_barrier(LogState),
    DurableProgress = maps:get(durable_progress, LogState),
    TargetProgress = maps:with(TargetReplicas, DurableProgress),
    bmscl_shard_rebalancer:begin_handoff(
      ShardScope,
      PlacementEpoch,
      OldReplicas,
      TargetReplicas,
      Barrier,
      TargetProgress);
begin_handoff(_, _, _, _) ->
    {error, invalid_queue_handoff}.

-spec record_target_progress(map(), binary(), non_neg_integer()) ->
    {ok, map()} | {error, term()}.
record_target_progress(ShardScope, Replica, DurableSequence) ->
    bmscl_shard_rebalancer:record_progress(
      ShardScope,
      Replica,
      DurableSequence).

-spec cutover(map(), pos_integer()) -> {ok, map()} | {error, term()}.
cutover(ShardScope, NextPlacementEpoch) ->
    bmscl_shard_rebalancer:cutover(ShardScope, NextPlacementEpoch).

-spec mark_retired(map(), binary()) -> {ok, map()} | {error, term()}.
mark_retired(ShardScope, Replica) ->
    bmscl_shard_rebalancer:mark_retired(ShardScope, Replica).

-ifdef(TEST).
-include_lib("eunit/include/eunit.hrl").

queue_handoff_uses_committed_prefix_and_verified_progress_test() ->
    PreviousStore = application:get_env(bmscl_supervisor, durable_store_module),
    application:set_env(
      bmscl_supervisor,
      durable_store_module,
      bmscl_durable_test_store),
    try
        ok = bmscl_durable_test_store:reset(),
        {ok, Log0} = bmscl_queue_quorum_log:new(
            <<"orders:0">>, 9, <<"a">>,
            [<<"a">>, <<"b">>, <<"c">>], 2, 1024),
        {ok, _, Log1} = bmscl_queue_quorum_log:append(
            Log0, 9, <<"a">>, 1000, <<"r1">>, <<"committed">>),
        {ok, Log2} = bmscl_queue_quorum_log:record_replica_durable(
            Log1, <<"b">>, 9, 1),
        {ok, _, Log3} = bmscl_queue_quorum_log:append(
            Log2, 9, <<"a">>, 1001, <<"r2">>, <<"pending">>),
        ?assertEqual(1, bmscl_queue_quorum_log:migration_barrier(Log3)),
        Scope = #{tenant_id => <<"tenant-a">>,
                  application_id => <<"queue-service">>,
                  namespace => <<"orders">>,
                  virtual_shard => 17},
        {ok, #{state := Handoff0}} = begin_handoff(
            Log3, Scope, 50, [<<"b">>, <<"c">>, <<"d">>]),
        ?assertEqual(1, maps:get(barrier_position, Handoff0)),
        Progress0 = maps:get(progress, Handoff0),
        ?assertEqual(1, maps:get(<<"b">>, Progress0)),
        ?assertEqual(0, maps:get(<<"c">>, Progress0)),
        ?assertEqual(0, maps:get(<<"d">>, Progress0)),
        {ok, _} = record_target_progress(Scope, <<"d">>, 1),
        ?assertEqual({error, target_not_caught_up}, cutover(Scope, 51)),
        {ok, _} = record_target_progress(Scope, <<"c">>, 1),
        {ok, #{owner_epoch := Epoch, state := Cutover}} = cutover(Scope, 51),
        ?assert(Epoch > 0),
        ?assertEqual(cutover, maps:get(phase, Cutover))
    after
        restore_store_env(PreviousStore)
    end.

restore_store_env(undefined) ->
    application:unset_env(bmscl_supervisor, durable_store_module);
restore_store_env({ok, Value}) ->
    application:set_env(bmscl_supervisor, durable_store_module, Value).

-endif.
