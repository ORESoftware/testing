%% Trusted local control bridge for the external host lifecycle agent.
%%
%% The bridge process owns the opaque quiesce handle. Erlang references and PIDs
%% never cross the Unix-domain socket. The wire protocol is deliberately tiny,
%% bounded, versioned, and payload-free:
%%
%%   v1 status\n
%%   v1 quiesce <timeout_ms>\n
%%   v1 resume\n
%%
%% SCINTILLA_LIFECYCLE_SOCKET is opt-in. Production uses one fixed cooperative
%% socket path under the product-owned setgid directory. Trusted host-control
%% uses a separate root-only sibling directory and never shares this writable
%% parent with the runtime. This module never removes a pre-existing path before
%% bind; stale cleanup is owned by the trusted service manager so an environment
%% variable cannot become an arbitrary-unlink seam.
%%
%% SCINTILLA_LIFECYCLE_AGENT_UID and SCINTILLA_LIFECYCLE_AGENT_GID are mandatory
%% whenever the socket is enabled and must both resolve to root identity. Every
%% accepted Linux AF_UNIX connection is authenticated with kernel SO_PEERCRED
%% before request bytes are parsed. The socket is 0660 so a capability-free root
%% lifecycle agent can reach it through a supplementary private control group;
%% group membership grants transport reachability only, never authorization.
-module(lambda_host_lifecycle_socket).
-behaviour(gen_server).

-export([
    start_link/0,
    enabled/0,
    parse_request_for_test/1,
    validate_socket_path_for_test/1,
    decode_peercred_for_test/1,
    authorize_peercred_for_test/2
]).
-export([init/1, handle_call/3, handle_cast/2, handle_info/2, terminate/2]).

-define(SERVER, ?MODULE).
-define(MAX_REQUEST_BYTES, 1024).
-define(MAX_QUIESCE_MS, 300000).
-define(RECV_TIMEOUT_MS, 5000).
-define(LIFECYCLE_SOCKET_PATH, "/run/scintilla-lifecycle/product/control.sock").
-define(SOL_SOCKET, 1).
-define(SO_PEERCRED, 17).
-define(PEERCRED_BYTES, 12).

start_link() ->
    gen_server:start_link({local, ?SERVER}, ?MODULE, [], []).

enabled() ->
    case socket_configuration() of
        {enabled, _Path, _TrustedPeer} -> true;
        disabled -> false;
        {error, _Reason} -> false
    end.

parse_request_for_test(Request) ->
    parse_request(Request).

validate_socket_path_for_test(Value) ->
    validate_socket_path(Value).

decode_peercred_for_test(Binary) ->
    decode_peercred(Binary).

authorize_peercred_for_test(Credentials, TrustedPeer) ->
    authorize_peercred(Credentials, TrustedPeer).

init([]) ->
    case socket_configuration() of
        disabled ->
            {ok, #{listener => undefined,
                   path => undefined,
                   trusted_peer => undefined,
                   quiesce_handle => undefined}};
        {error, Reason} ->
            {stop, Reason};
        {enabled, Path, TrustedPeer} ->
            case open_listener(Path) of
                {ok, Listener} ->
                    Server = self(),
                    _Acceptor = spawn_link(
                        fun() -> accept_loop(Listener, Server, TrustedPeer) end
                    ),
                    {ok, #{listener => Listener,
                           path => Path,
                           trusted_peer => TrustedPeer,
                           quiesce_handle => undefined}};
                {error, Reason} ->
                    {stop, Reason}
            end
    end.

handle_call({protocol, status}, _From, State) ->
    {reply, status_response(), State};
handle_call({protocol, {quiesce, TimeoutMs}}, _From, State) ->
    case maps:get(quiesce_handle, State) of
        undefined ->
            case lambda_host_lifecycle:quiesce(TimeoutMs) of
                {ok, Handle} ->
                    {reply, <<"ok sealed\n">>, State#{quiesce_handle := Handle}};
                {error, drain_timeout} ->
                    {reply, <<"error drain_timeout\n">>, State};
                {error, already_quiescing} ->
                    {reply, <<"error lifecycle_busy\n">>, State};
                {error, already_sealed} ->
                    {reply, <<"error lifecycle_busy\n">>, State};
                {error, invalid_quiesce_handle} ->
                    {reply, <<"error demand_returned\n">>, State};
                {error, _Reason} ->
                    {reply, <<"error lifecycle_unavailable\n">>, State}
            end;
        Handle ->
            case lambda_host_lifecycle:quiesce_status(Handle) of
                {ok, #{admission := sealed}} ->
                    {reply, <<"ok sealed\n">>, State};
                {ok, #{admission := quiescing}} ->
                    {reply, <<"error lifecycle_busy\n">>, State};
                {error, invalid_quiesce_handle} ->
                    {reply, <<"error demand_returned\n">>,
                     State#{quiesce_handle := undefined}};
                {error, _Reason} ->
                    {reply, <<"error lifecycle_unavailable\n">>,
                     State#{quiesce_handle := undefined}}
            end
    end;
handle_call({protocol, resume}, _From, State) ->
    case maps:get(quiesce_handle, State) of
        undefined ->
            {reply, <<"ok running\n">>, State};
        Handle ->
            case lambda_host_lifecycle:resume(Handle) of
                ok ->
                    {reply, <<"ok running\n">>, State#{quiesce_handle := undefined}};
                {error, _Reason} ->
                    {reply, <<"error lifecycle_unavailable\n">>, State#{quiesce_handle := undefined}}
            end
    end;
handle_call({protocol, invalid}, _From, State) ->
    {reply, <<"error invalid_request\n">>, State};
handle_call(_Request, _From, State) ->
    {reply, <<"error unsupported\n">>, State}.

handle_cast(_Message, State) ->
    {noreply, State}.

handle_info(_Message, State) ->
    {noreply, State}.

terminate(_Reason, State) ->
    case maps:get(listener, State, undefined) of
        undefined -> ok;
        Listener -> _ = socket:close(Listener)
    end,
    %% A path is recorded in State only after this process successfully bound it.
    %% open_listener/1 never removes a pre-existing filesystem entry.
    case maps:get(path, State, undefined) of
        undefined -> ok;
        Path -> _ = file:delete(Path)
    end,
    ok.

open_listener(Path) ->
    case socket:is_supported(local) of
        false ->
            {error, local_socket_unsupported};
        true ->
            %% Never unlink before bind. If anything already occupies the fixed
            %% path, startup fails closed and systemd RuntimeDirectory cleanup is
            %% responsible for removing stale runtime-owned sockets.
            case socket:open(local, stream, default) of
                {error, Reason} ->
                    {error, {socket_open_failed, Reason}};
                {ok, Listener} ->
                    bind_listener(Listener, Path)
            end
    end.

bind_listener(Listener, Path) ->
    Address = #{family => local, path => Path},
    case socket:bind(Listener, Address) of
        {error, Reason} ->
            _ = socket:close(Listener),
            {error, {socket_bind_failed, Reason}};
        ok ->
            listen_bound_socket(Listener, Path)
    end.

listen_bound_socket(Listener, Path) ->
    case socket:listen(Listener, 16) of
        {error, Reason} ->
            _ = socket:close(Listener),
            _ = file:delete(Path),
            {error, {socket_listen_failed, Reason}};
        ok ->
            %% The setgid product parent directory supplies the private
            %% lifecycle-control group. 0660 lets a capless root host agent reach
            %% the socket through a supplementary group. Kernel SO_PEERCRED remains
            %% authoritative; the root-only host directory is not visible here.
            case file:change_mode(Path, 8#660) of
                ok -> {ok, Listener};
                {error, Reason} ->
                    _ = socket:close(Listener),
                    _ = file:delete(Path),
                    {error, {socket_mode_failed, Reason}}
            end
    end.

accept_loop(Listener, Server, TrustedPeer) ->
    case socket:accept(Listener) of
        {ok, Connection} ->
            _Worker = spawn(
                fun() -> serve_connection(Connection, Server, TrustedPeer) end
            ),
            accept_loop(Listener, Server, TrustedPeer);
        {error, closed} ->
            ok;
        {error, _Reason} ->
            exit(lifecycle_socket_accept_failed)
    end.

serve_connection(Connection, Server, TrustedPeer) ->
    case authorize_connection(Connection, TrustedPeer) of
        ok ->
            Response = case recv_line(Connection, <<>>) of
                {ok, Request} ->
                    ProtocolRequest = parse_request(Request),
                    gen_server:call(
                        Server,
                        {protocol, ProtocolRequest},
                        ?MAX_QUIESCE_MS + 10000
                    );
                {error, _Reason} ->
                    <<"error invalid_request\n">>
            end,
            _ = socket:send(Connection, Response),
            _ = socket:close(Connection),
            ok;
        {error, _Reason} ->
            %% Fail closed without reading request bytes or sending a response.
            %% An unauthorized local process must not get a lifecycle protocol oracle.
            _ = socket:close(Connection),
            ok
    end.

authorize_connection(Connection, TrustedPeer) ->
    case os:type() of
        {unix, linux} ->
            case socket:getopt_native(
                Connection,
                {?SOL_SOCKET, ?SO_PEERCRED},
                ?PEERCRED_BYTES
            ) of
                {ok, Binary} ->
                    authorize_peercred(decode_peercred(Binary), TrustedPeer);
                {error, Reason} ->
                    {error, {peercred_failed, Reason}}
            end;
        _Other ->
            {error, peercred_unsupported}
    end.

decode_peercred(
    <<Pid:32/native-signed-integer,
      Uid:32/native-unsigned-integer,
      Gid:32/native-unsigned-integer>>
) when Pid > 0 ->
    {ok, #{pid => Pid, uid => Uid, gid => Gid}};
decode_peercred(_Other) ->
    {error, invalid_peercred}.

authorize_peercred({ok, #{uid := Uid, gid := Gid}}, #{uid := Uid, gid := Gid}) ->
    ok;
authorize_peercred({ok, _Credentials}, _TrustedPeer) ->
    {error, unauthorized_peer};
authorize_peercred({error, _Reason} = Error, _TrustedPeer) ->
    Error.

recv_line(_Connection, Acc) when byte_size(Acc) > ?MAX_REQUEST_BYTES ->
    {error, request_too_large};
recv_line(Connection, Acc) ->
    case binary:match(Acc, <<"\n">>) of
        {Index, 1} ->
            {ok, binary:part(Acc, 0, Index)};
        nomatch ->
            case socket:recv(Connection, 0, ?RECV_TIMEOUT_MS) of
                {ok, Data} when is_binary(Data), byte_size(Data) > 0 ->
                    recv_line(Connection, <<Acc/binary, Data/binary>>);
                {ok, _Empty} ->
                    {error, closed};
                {error, Reason} ->
                    {error, Reason}
            end
    end.

parse_request(Request0) when is_binary(Request0) ->
    Request = trim_ascii(Request0),
    case binary:split(Request, <<" ">>, [global]) of
        [<<"v1">>, <<"status">>] ->
            status;
        [<<"v1">>, <<"resume">>] ->
            resume;
        [<<"v1">>, <<"quiesce">>, Timeout] ->
            parse_quiesce_timeout(Timeout);
        _ ->
            invalid
    end;
parse_request(_Request) ->
    invalid.

parse_quiesce_timeout(Timeout) ->
    case string:to_integer(binary_to_list(Timeout)) of
        {Value, []} when Value >= 0, Value =< ?MAX_QUIESCE_MS ->
            {quiesce, Value};
        _ ->
            invalid
    end.

status_response() ->
    Status = lambda_host_lifecycle:status(),
    Admission = admission_token(maps:get(admission, Status, unavailable)),
    InFlight = maps:get(in_flight, Status, 0),
    QueueDepth = maps:get(queue_depth, Status, 0),
    IdleForMs = maps:get(idle_for_ms, Status, 0),
    iolist_to_binary(io_lib:format(
        "ok status ~s ~B ~B ~B~n",
        [Admission, InFlight, QueueDepth, IdleForMs]
    )).

admission_token(accepting) -> <<"accepting">>;
admission_token(quiescing) -> <<"quiescing">>;
admission_token(sealed) -> <<"sealed">>;
admission_token(_) -> <<"unavailable">>.

socket_configuration() ->
    case socket_path() of
        disabled ->
            disabled;
        {error, _Reason} = Error ->
            Error;
        {enabled, Path} ->
            case trusted_peer() of
                {ok, TrustedPeer} -> {enabled, Path, TrustedPeer};
                {error, _Reason} = Error -> Error
            end
    end.

socket_path() ->
    case os:getenv("SCINTILLA_LIFECYCLE_SOCKET") of
        false ->
            disabled;
        "" ->
            disabled;
        Value ->
            validate_socket_path(Value)
    end.

validate_socket_path(?LIFECYCLE_SOCKET_PATH = Value) ->
    {enabled, Value};
validate_socket_path(_Value) ->
    {error, invalid_lifecycle_socket_path}.

trusted_peer() ->
    case {
        parse_peer_id(os:getenv("SCINTILLA_LIFECYCLE_AGENT_UID")),
        parse_peer_id(os:getenv("SCINTILLA_LIFECYCLE_AGENT_GID"))
    } of
        {{ok, 0}, {ok, 0}} ->
            {ok, #{uid => 0, gid => 0}};
        {{ok, _Uid}, {ok, _Gid}} ->
            {error, lifecycle_agent_must_be_root};
        _ ->
            {error, invalid_lifecycle_agent_peer}
    end.

parse_peer_id(false) ->
    {error, missing};
parse_peer_id("") ->
    {error, missing};
parse_peer_id(Value) when is_list(Value) ->
    case string:to_integer(Value) of
        {Id, []} when Id >= 0, Id =< 4294967295 ->
            {ok, Id};
        _ ->
            {error, invalid}
    end.

trim_ascii(Binary) ->
    unicode:characters_to_binary(string:trim(binary_to_list(Binary))).
