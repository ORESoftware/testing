-module(bmscl_layout_proof_tests).

-include_lib("eunit/include/eunit.hrl").

actor_and_turn_token_are_layout_pinned_test() ->
    Placement = #{
        actor_key => {<<"tenant-a">>, <<"app-a">>, <<"orders">>, 0},
        tenant_id => <<"tenant-a">>,
        application_id => <<"app-a">>,
        namespace => <<"orders">>,
        object_key => <<"order-9">>,
        layout_version => 1,
        virtual_shard => 17,
        actor_bucket => 0,
        virtual_shards => 64,
        shards_per_actor => 64,
        deployment_id => <<"sha256:test">>
    },
    {ok, Pid} = bmscl_durable_actor:start(Placement, 7),
    try
        {ok, Token} = bmscl_durable_actor:admit_turn(Pid, Placement),
        ?assertEqual(1, maps:get(layout_version, Token)),
        ?assertEqual(
           {error, tenant_or_actor_mismatch},
           bmscl_durable_actor:admit_turn(Pid, Placement#{layout_version => 2})),
        ?assertEqual(
           {error, stale_or_invalid_turn_token},
           bmscl_durable_actor:load_state(
             Pid,
             Placement,
             Token#{layout_version => 2}))
    after
        gen_server:stop(Pid)
    end.
