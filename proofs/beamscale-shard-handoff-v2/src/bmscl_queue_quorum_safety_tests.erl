-module(bmscl_queue_quorum_safety_tests).

-include_lib("eunit/include/eunit.hrl").

old_term_quorum_does_not_commit_until_current_term_entry_test() ->
    {ok, S0} = bmscl_queue_quorum_log:new(
        <<"orders:7">>,
        7,
        <<"a">>,
        [<<"a">>, <<"b">>, <<"c">>],
        2,
        64),
    {ok, _, S1} = bmscl_queue_quorum_log:append(
        S0,
        7,
        <<"a">>,
        1000,
        <<"old-term">>,
        <<"one">>),
    {ok, S2} = bmscl_queue_quorum_log:begin_term(S1, 8, <<"b">>, 1),
    {ok, S3} = bmscl_queue_quorum_log:record_replica_durable(
        S2,
        <<"c">>,
        8,
        1),
    ?assertEqual(0, bmscl_queue_quorum_log:migration_barrier(S3)),
    {ok, #{sequence := 2, status := pending}, S4} = bmscl_queue_quorum_log:append(
        S3,
        8,
        <<"b">>,
        1001,
        <<"current-term">>,
        <<"two">>),
    {ok, S5} = bmscl_queue_quorum_log:record_replica_durable(
        S4,
        <<"c">>,
        8,
        2),
    ?assertEqual(2, bmscl_queue_quorum_log:migration_barrier(S5)),
    ?assertEqual({ok, committed}, bmscl_queue_quorum_log:append_status(S5, 1)),
    ?assertEqual({ok, committed}, bmscl_queue_quorum_log:append_status(S5, 2)).
