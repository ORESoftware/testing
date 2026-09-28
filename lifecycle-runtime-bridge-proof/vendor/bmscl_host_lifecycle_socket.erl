%% Trusted local control bridge for the external BeamScale host lifecycle agent.
%%
%% This process, not a short-lived socket connection, owns the opaque
%% `bmscl_runtime:quiesce/1` handle. Erlang PIDs/references never cross the wire.
%% The protocol intentionally matches Scintilla so the shared host daemon needs
%% only one product-control adapter:
%%
%%   v1 status\n
%%   v1 quiesce <timeout_ms>\n
%%   v1 resume\n
%%
%% BMSCL_LIFECYCLE_SOCKET is opt-in. Production uses one fixed cooperative
%% socket path under the product-owned setgid directory. Trusted host-control
%% uses a separate root-only sibling directory and never shares this writable
%% parent with the supervisor.
%% This module never unlinks a pre-existing path before bind: a stale or
%% attacker-created path fails startup closed and must be cleaned by the trusted
%% service manager/runtime-directory owner.
%%
%% Filesystem group access is transport reachability only. Every accepted Linux
%% AF_UNIX connection is authenticated with kernel SO_PEERCRED before request
%% bytes are parsed and only host uid=0,gid=0 is authorized. The root lifecycle
%% agent may use a supplementary lifecycle-control group to reach the 0660 socket
%% without receiving CAP_DAC_OVERRIDE. Unauthorized peers are closed silently.
-module(bmscl_host_lifecycle_socket).
-behaviour(gen_server).

-export([
    start_link/0,
    enabled/0,
    parse_request_for_test/1,
    validate_socket_path_for_test/1,
    authorize_peercred_for_test/1
]).
-export([init/1, handle_call/3, handle_cast/2, handle_info/2, terminate/2]).

-define(SERVER, ?MODULE).
-define(MAX_REQUEST_BYTES, 1024).
-define(MAX_QUIESCE_MS, 300000).
-define(RECV_TIMEOUT_MS, 5000).
-define(LIFECYCLE_SOCKET_PATH, "/run/beamscale-lifecycle/product/control.sock").
%% Linux SOL_SOCKET / SO_PEERCRED. Erlang's named peercred option is exposed in
%% the type surface but is intentionally not wired by the socket NIF, so use the
%% Linux-native read-only option explicitly.
-define(SOL_SOCKET, 1).
-define(SO_PEERCRED, 17).
-define(PEERCRED_BYTES, 12).

start_link() ->
    gen_server:start_link({local, ?SERVER}, ?MODULE, [], []).

enabled() ->
    case socket_path() of
        {enabled, _Path} -> true;
        disabled -> false;
        {error, _Reason} -> false
    end.

parse_request_for_test(Request) ->
    parse_request(Request).

validate_socket_path_for_test(Value) ->
    validate_socket_path(Value).

authorize_peercred_for_test(Binary) ->
    authorize_peercred(decode_peercred(Binary)).

init([]) ->
    case socket_path() of
        disabled ->
            {ok, initial_state(undefined, undefined)};
        {error, Reason} ->
            {stop, Reason};
        {enabled, Path} ->
            case open_listener(Path) of
                {ok, Listener} ->
                    Server = self(),
                    _Acceptor = spawn_link(fun() -> accept_loop(Listener, Server) end),
                    {ok, initial_state(Listener, Path)};
                {error, Reason} ->
                    {stop, Reason}
            end
    end.

handle_call({protocol, status}, _From, State0) ->
    {Response, State1} = status_response(State0),
    {reply, Response, State1};
handle_call({protocol, {quiesce, TimeoutMs}}, _From, State0) ->
    case maps:get(quiesce_handle, State0) of
        undefined ->
            case bmscl_runtime:quiesce(TimeoutMs) of
                {ok, Handle} ->
                    State1 = State0#{quiesce_handle := Handle, idle_since_ms := undefined},
                    {reply, <<"ok sealed\n">>, State1};
                {error, drain_timeout} ->
                    {reply, <<"error drain_timeout\n">>, State0};
                {error, runtime_changed} ->
                    {reply, <<"error runtime_changed\n">>, State0};
                {error, _Reason} ->
                    {reply, <<"error lifecycle_unavailable\n">>, State0}
            end;
        _Handle ->
            {reply, <<"ok sealed\n">>, State0}
    end;
handle_call({protocol, resume}, _From, State0) ->
    case maps:get(quiesce_handle, State0) of
        undefined ->
            {reply, <<"ok running\n">>, State0};
        Handle ->
            case bmscl_runtime:resume(Handle) of
                ok ->
                    {reply, <<"ok running\n">>, reset_idle(State0#{quiesce_handle := undefined})};
                {error, runtime_changed} ->
                    {reply, <<"error runtime_changed\n">>, State0#{quiesce_handle := undefined}};
                {error, _Reason} ->
                    {reply, <<"error lifecycle_unavailable\n">>, State0#{quiesce_handle := undefined}}
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
    %% Only delete a path after this process successfully bound it. open_listener/1
    %% never removes a pre-existing filesystem entry, so this cannot be used as a
    %% privileged arbitrary-unlink primitive through the environment variable.
    case maps:get(path, State, undefined) of
        undefined -> ok;
        Path -> _ = file:delete(Path)
    end,
    ok.

initial_state(Listener, Path) ->
    #{listener => Listener,
      path => Path,
      quiesce_handle => undefined,
      idle_since_ms => undefined}.

status_response(State0) ->
    case catch bmscl_deployment_manager:status() of
        Status when is_map(Status) ->
            Blockers = maps:get(drain_blockers, Status, 0),
            Admission = normalize_admission(maps:get(admission, Status, unavailable)),
            State1 = update_idle(State0, Admission, Blockers),
            IdleForMs = idle_for_ms(State1),
            Response = iolist_to_binary(io_lib:format(
                "ok status ~s ~B 0 ~B~n",
                [admission_token(Admission), Blockers, IdleForMs]
            )),
            {Response, State1};
        _Error ->
            {<<"error lifecycle_unavailable\n">>, reset_idle(State0)}
    end.

normalize_admission(accepting) -> accepting;
normalize_admission(draining) -> quiescing;
normalize_admission(sealed) -> sealed;
normalize_admission(_) -> unavailable.

update_idle(State, accepting, 0) ->
    case maps:get(idle_since_ms, State) of
        undefined -> State#{idle_since_ms := now_ms()};
        _Existing -> State
    end;
update_idle(State, _Admission, _Blockers) ->
    reset_idle(State).

reset_idle(State) ->
    State#{idle_since_ms := undefined}.

idle_for_ms(State) ->
    case maps:get(idle_since_ms, State) of
        undefined -> 0;
        Since -> erlang:max(0, now_ms() - Since)
    end.

now_ms() ->
    erlang:monotonic_time(millisecond).

open_listener(Path) ->
    case socket:is_supported(local) of
        false ->
            {error, local_socket_unsupported};
        true ->
            %% Do not unlink here. A pre-existing path may be a file/symlink/socket
            %% planted outside this process's authority. Bind must fail closed and
            %% trusted systemd RuntimeDirectory ownership handles stale cleanup.
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
            %% the socket through a supplementary group. SO_PEERCRED remains the
            %% authorization layer: only uid=0,gid=0 is accepted after connect.
            case file:change_mode(Path, 8#660) of
                ok -> {ok, Listener};
                {error, Reason} ->
                    _ = socket:close(Listener),
                    _ = file:delete(Path),
                    {error, {socket_mode_failed, Reason}}
            end
    end.

accept_loop(Listener, Server) ->
    case socket:accept(Listener) of
        {ok, Connection} ->
            _Worker = spawn(fun() -> serve_connection(Connection, Server) end),
            accept_loop(Listener, Server);
        {error, closed} ->
            ok;
        {error, _Reason} ->
            exit(lifecycle_socket_accept_failed)
    end.

serve_connection(Connection, Server) ->
    case authorize_connection(Connection) of
        ok ->
            Response = case recv_line(Connection, <<>>) of
                {ok, Request} ->
                    ProtocolRequest = parse_request(Request),
                    gen_server:call(
                      Server,
                      {protocol, ProtocolRequest},
                      ?MAX_QUIESCE_MS + 10000);
                {error, _Reason} ->
                    <<"error invalid_request\n">>
            end,
            _ = socket:send(Connection, Response),
            _ = socket:close(Connection),
            ok;
        {error, _Reason} ->
            %% Do not read attacker-controlled bytes and do not provide a
            %% lifecycle protocol oracle to an unauthorized local process.
            _ = socket:close(Connection),
            ok
    end.

authorize_connection(Connection) ->
    case os:type() of
        {unix, linux} ->
            case socket:getopt_native(
                   Connection,
                   {?SOL_SOCKET, ?SO_PEERCRED},
                   ?PEERCRED_BYTES) of
                {ok, Binary} -> authorize_peercred(decode_peercred(Binary));
                {error, Reason} -> {error, {peercred_failed, Reason}}
            end;
        _Other ->
            {error, peercred_unsupported}
    end.

decode_peercred(
  <<Pid:32/native-signed-integer,
    Uid:32/native-unsigned-integer,
    Gid:32/native-unsigned-integer>>) ->
    {ok, #{pid => Pid, uid => Uid, gid => Gid}};
decode_peercred(_Other) ->
    {error, invalid_peercred}.

authorize_peercred({ok, #{pid := Pid, uid := 0, gid := 0}}) when Pid > 0 ->
    ok;
authorize_peercred({ok, _Credentials}) ->
    {error, unauthorized_peer};
authorize_peercred({error, _Reason} = Error) ->
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
        [<<"v1">>, <<"status">>] -> status;
        [<<"v1">>, <<"resume">>] -> resume;
        [<<"v1">>, <<"quiesce">>, Timeout] -> parse_quiesce_timeout(Timeout);
        _ -> invalid
    end;
parse_request(_Request) ->
    invalid.

parse_quiesce_timeout(Timeout) ->
    case string:to_integer(binary_to_list(Timeout)) of
        {Value, []} when Value >= 0, Value =< ?MAX_QUIESCE_MS -> {quiesce, Value};
        _ -> invalid
    end.

admission_token(accepting) -> <<"accepting">>;
admission_token(quiescing) -> <<"quiescing">>;
admission_token(sealed) -> <<"sealed">>;
admission_token(_) -> <<"unavailable">>.

socket_path() ->
    case os:getenv("BMSCL_LIFECYCLE_SOCKET") of
        false -> disabled;
        "" -> disabled;
        Value -> validate_socket_path(Value)
    end.

validate_socket_path(?LIFECYCLE_SOCKET_PATH = Value) ->
    {enabled, Value};
validate_socket_path(_Value) ->
    {error, invalid_lifecycle_socket_path}.

trim_ascii(Binary) ->
    unicode:characters_to_binary(string:trim(binary_to_list(Binary))).
