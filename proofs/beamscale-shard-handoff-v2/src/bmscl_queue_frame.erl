-module(bmscl_queue_frame).

-export([encode/5, decode/1, checksum/1, header_size/0, checksum_size/0]).

-define(VERSION, 1).
-define(FLAGS, 0).
-define(HEADER_SIZE, 38).
-define(CHECKSUM_SIZE, 32).
-define(MAX_KEY_BYTES, 1024).
-define(MAX_PAYLOAD_BYTES, 16777216).
-define(MAX_SAFE_INTEGER, 9007199254740991).

-spec encode(pos_integer(), pos_integer(), non_neg_integer(), binary(), binary()) ->
    {ok, binary()} | {error, term()}.
encode(Term, Sequence, TimestampMs, IdempotencyKey, Payload)
  when is_integer(Term), Term > 0, Term =< ?MAX_SAFE_INTEGER,
       is_integer(Sequence), Sequence > 0, Sequence =< ?MAX_SAFE_INTEGER,
       is_integer(TimestampMs), TimestampMs >= 0,
       is_binary(IdempotencyKey),
       byte_size(IdempotencyKey) =< ?MAX_KEY_BYTES,
       is_binary(Payload),
       byte_size(Payload) =< ?MAX_PAYLOAD_BYTES ->
    KeyLength = byte_size(IdempotencyKey),
    PayloadLength = byte_size(Payload),
    Body = <<
        "BMSQ",
        ?VERSION:8/unsigned,
        ?FLAGS:8/unsigned,
        Term:64/unsigned-big,
        Sequence:64/unsigned-big,
        TimestampMs:64/unsigned-big,
        KeyLength:32/unsigned-big,
        PayloadLength:32/unsigned-big,
        IdempotencyKey/binary,
        Payload/binary
    >>,
    {ok, <<Body/binary, (checksum(Body))/binary>>};
encode(_, _, _, _, _) ->
    {error, invalid_queue_record}.

-spec decode(binary()) -> {ok, map()} | {error, term()}.
decode(Frame) when is_binary(Frame),
                   byte_size(Frame) >= ?HEADER_SIZE + ?CHECKSUM_SIZE ->
    BodySize = byte_size(Frame) - ?CHECKSUM_SIZE,
    <<Body:BodySize/binary, ExpectedChecksum:?CHECKSUM_SIZE/binary>> = Frame,
    case checksum(Body) =:= ExpectedChecksum of
        false ->
            {error, checksum_mismatch};
        true ->
            decode_verified(Body, ExpectedChecksum)
    end;
decode(_) ->
    {error, invalid_queue_frame}.

-spec checksum(binary()) -> binary().
checksum(Bytes) when is_binary(Bytes) ->
    crypto:hash(sha256, Bytes).

-spec header_size() -> pos_integer().
header_size() ->
    ?HEADER_SIZE.

-spec checksum_size() -> pos_integer().
checksum_size() ->
    ?CHECKSUM_SIZE.

decode_verified(<<
    "BMSQ",
    ?VERSION:8/unsigned,
    ?FLAGS:8/unsigned,
    Term:64/unsigned-big,
    Sequence:64/unsigned-big,
    TimestampMs:64/unsigned-big,
    KeyLength:32/unsigned-big,
    PayloadLength:32/unsigned-big,
    Rest/binary
>>, Digest)
  when Term > 0, Term =< ?MAX_SAFE_INTEGER,
       Sequence > 0, Sequence =< ?MAX_SAFE_INTEGER,
       KeyLength =< ?MAX_KEY_BYTES,
       PayloadLength =< ?MAX_PAYLOAD_BYTES ->
    ExpectedRestSize = KeyLength + PayloadLength,
    case byte_size(Rest) =:= ExpectedRestSize of
        true ->
            <<IdempotencyKey:KeyLength/binary, Payload:PayloadLength/binary>> = Rest,
            {ok, #{
                term => Term,
                sequence => Sequence,
                timestamp_unix_ms => TimestampMs,
                idempotency_key => IdempotencyKey,
                payload => Payload,
                checksum => Digest
            }};
        false ->
            {error, invalid_queue_frame_length}
    end;
decode_verified(<<"BMSQ", Version:8/unsigned, _/binary>>, _Digest)
  when Version =/= ?VERSION ->
    {error, {unsupported_queue_frame_version, Version}};
decode_verified(_, _) ->
    {error, invalid_queue_frame_header}.

-ifdef(TEST).
-include_lib("eunit/include/eunit.hrl").

deterministic_frame_round_trip_test() ->
    {ok, FrameA} = encode(7, 42, 1700000000123, <<"producer:req-1">>, <<"hello">>),
    {ok, FrameB} = encode(7, 42, 1700000000123, <<"producer:req-1">>, <<"hello">>),
    ?assertEqual(FrameA, FrameB),
    {ok, Decoded} = decode(FrameA),
    ?assertEqual(7, maps:get(term, Decoded)),
    ?assertEqual(42, maps:get(sequence, Decoded)),
    ?assertEqual(<<"producer:req-1">>, maps:get(idempotency_key, Decoded)),
    ?assertEqual(<<"hello">>, maps:get(payload, Decoded)).

corrupt_record_is_rejected_test() ->
    {ok, Frame} = encode(1, 1, 5, <<>>, <<"abc">>),
    Size = byte_size(Frame),
    <<Prefix:(Size - 33)/binary, Byte:8, Tail:32/binary>> = Frame,
    Corrupt = <<Prefix/binary, (Byte bxor 1):8, Tail/binary>>,
    ?assertEqual({error, checksum_mismatch}, decode(Corrupt)).

truncated_record_is_rejected_test() ->
    {ok, Frame} = encode(1, 1, 5, <<>>, <<"abc">>),
    TruncatedSize = byte_size(Frame) - 1,
    <<Truncated:TruncatedSize/binary, _/binary>> = Frame,
    ?assertMatch({error, _}, decode(Truncated)).

-endif.
