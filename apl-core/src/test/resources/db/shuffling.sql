delete from shuffling;
delete from shuffling_participant;

INSERT into shuffling
(db_id,         id,         holding_id, holding_type, issuer_id, amount,      participant_count,   blocks_remaining, registrant_count,  stage, assignee_account_id, height, latest, recipient_public_keys) VALUES
(100,           20000,      null,       0,             3500,    2500,         3,                   1,                   3,               2,      null,              996 ,    false,        (X'51546eb53e8439f156acd2a7b7301cadec13d0ff85f46ff0cc97005ae16776b7', X'4d04aabfa6588d866f8eaa3ebc30ae5012b49cd1bd7667c716068f16a79303ec', X'977f3b11ad0373a63688dd416f991d2447bddaf3660403077282bb8bad9c01ab')),
(110,           10000,      500,        1,             2500,    100000,       10,                  120,                 2,               0,      null,              999 ,    false,        null                   ),
(120,           30000,      null,       0,             1500,    2500,         5,                   120,                 2,               0,      null,              1000,    false,        null                   ),
(130,           20000,      null,       0,             3500,    2500,         3,                   null,                3,               5,      null,              1000,    false,        (X'51546eb53e8439f156acd2a7b7301cadec13d0ff85f46ff0cc97005ae16776b7', X'4d04aabfa6588d866f8eaa3ebc30ae5012b49cd1bd7667c716068f16a79303ec', X'977f3b11ad0373a63688dd416f991d2447bddaf3660403077282bb8bad9c01ab')),
(140,           40000,      null,       0,             2500,    5000,         3,                   118 ,                3,               2,      2500,              1000,    false,        null                   ),
(150,           50000,      null,       0,             2500,    1000,         3,                   120,                 3,               1,      1500,              1000,    true ,        null                   ),
(160,           60000,      1000,       2,             3500,    2000,         3,                   1440,                1,               0,      null,              1001,    true ,        null                   ),
(170,           70000,      1000,       2,             1500,    2000,         3,                   1   ,                3,               2,      null,              1001,    false,        (X'51546eb53e8439f156acd2a7b7301cadec13d0ff85f46ff0cc97005ae16776b7', X'4d04aabfa6588d866f8eaa3ebc30ae5012b49cd1bd7667c716068f16a79303ec', X'977f3b11ad0373a63688dd416f991d2447bddaf3660403077282bb8bad9c01ab')),
(180,           80000,      1000,       2,             1500,    3000,         4,                   110 ,                4,               1,      2500,              1001,    true ,        null                   ),
(190,           40000,      null,       0,             2500,    5000,         3,                   null,                3,               5,      null,              1001,    true ,        (X'51546eb53e8439f156acd2a7b7301cadec13d0ff85f46ff0cc97005ae16776b7', X'4d04aabfa6588d866f8eaa3ebc30ae5012b49cd1bd7667c716068f16a79303ec', X'977f3b11ad0373a63688dd416f991d2447bddaf3660403077282bb8bad9c01ab')),
(200,           30000,      null,       0,             1500,    2500,         5,                   119,                 2,               0,      null,              1002,    false,        null                   ),
(210,           70000,      1000,       2,             1500,    2000,         3,                   null,                3,               5,      null,              1002,    true ,        (X'51546eb53e8439f156acd2a7b7301cadec13d0ff85f46ff0cc97005ae16776b7', X'4d04aabfa6588d866f8eaa3ebc30ae5012b49cd1bd7667c716068f16a79303ec', X'977f3b11ad0373a63688dd416f991d2447bddaf3660403077282bb8bad9c01ab')),
(220,           10000,      500,        1,             2500,    100000,       10,                  118,                 5,               0,      null,              1003,    true ,        null                   ),
(230,           30000,      null,       0,             1500,    2500,         5,                   118,                 3,               0,      null,              1005,    true ,        null                   ),
;

insert into shuffling_participant
(latest ,db_id,         height,         shuffling_id,      account_id,         participant_index,          next_account_id,    state,          data_transaction_full_hash,                                           data_hash,                                                           blame_data,                             key_seeds) VALUES
(FALSE  ,920           ,850            ,12345              ,3500               ,0                          ,1500               ,2              ,X'5443a31df402f76ef4ff80fe7cbda419737b4d8933958fd5ce2c7e19b7daa58b'  ,X'b08dcd79a56d5ae3c92b6b25e60237d45a9c5e699174da17abf8b39bb2737afd'   ,null                                   ,null    ),
(FALSE  ,930           ,900            ,12345              ,3500               ,0                          ,1500               ,2              ,X'5443a31df402f76ef4ff80fe7cbda419737b4d8933958fd5ce2c7e19b7daa58b'  ,X'b08dcd79a56d5ae3c92b6b25e60237d45a9c5e699174da17abf8b39bb2737afd'   ,null                                   ,null    ),
(FALSE  ,940           ,994            ,20000              ,3500               ,0                          ,2500               ,0              ,X'6226f1365b784db87fa749ca6f793ebb9d1d7bed51ffd050d2c8767b4c4f9ec6'  ,X'00a3021a6e02ffe456d930bc789640b7b12b833d1bb621c781112f6007070d15'   ,null                                   ,null    ),
(FALSE  ,950           ,995            ,20000              ,2500               ,1                          ,1500               ,0              ,X'41a7ed1ba26217cf70059964c74665d0a9c364a4078f69a3ca6d1e2623b0679f'  ,X'3f048d92be9bf806374a1823d99cfdbe1c59fbcbdf1de40001f4e39df523a4e7'   ,null                                   ,null    ),
(FALSE  ,960           ,996            ,20000              ,1500               ,2                          ,null               ,0              ,X'ecf13d204f0d98964d34959f2e5565cf9a909a5d1592004054a470e12147b6ab'  ,X'9ad9df94c1225e30c0d7cddd79a676c6a42371c52780031610941783eac8b9d1'   ,null                                   ,null    ),
(FALSE  ,970           ,997            ,20000              ,3500               ,0                          ,2500               ,1              ,X'6226f1365b784db87fa749ca6f793ebb9d1d7bed51ffd050d2c8767b4c4f9ec6'  ,X'00a3021a6e02ffe456d930bc789640b7b12b833d1bb621c781112f6007070d15'   ,null                                   ,null    ),
(FALSE  ,980           ,998            ,20000              ,2500               ,1                          ,1500               ,1              ,X'41a7ed1ba26217cf70059964c74665d0a9c364a4078f69a3ca6d1e2623b0679f'  ,X'3f048d92be9bf806374a1823d99cfdbe1c59fbcbdf1de40001f4e39df523a4e7'   ,null                                   ,null    ),
(TRUE   ,990           ,999            ,20000              ,1500               ,2                          ,null               ,1              ,X'ecf13d204f0d98964d34959f2e5565cf9a909a5d1592004054a470e12147b6ab'  ,X'9ad9df94c1225e30c0d7cddd79a676c6a42371c52780031610941783eac8b9d1'   ,null                                   ,null    ),
(FALSE  ,1000          ,999            ,10000              ,2500               ,0                          ,null               ,0              ,null                                                                 ,null                                                                  ,null                                   ,null    ),
(TRUE   ,1010          ,1000           ,30000              ,1500               ,0                          ,null               ,0              ,null                                                                 ,null                                                                  ,null                                   ,null    ),
(TRUE   ,1020          ,1000           ,70000              ,1500               ,0                          ,null               ,0              ,null                                                                 ,null                                                                  ,null                                   ,null    ),
(TRUE   ,1030          ,1000           ,80000              ,1500               ,0                          ,null               ,0              ,null                                                                 ,null                                                                  ,null                                   ,null    ),
(TRUE   ,1040          ,1000           ,20000              ,3500               ,0                          ,2500               ,2              ,X'6226f1365b784db87fa749ca6f793ebb9d1d7bed51ffd050d2c8767b4c4f9ec6'  ,X'00a3021a6e02ffe456d930bc789640b7b12b833d1bb621c781112f6007070d15'   ,null                                   ,null    ),
(TRUE   ,1050          ,1000           ,20000              ,2500               ,1                          ,1500               ,2              ,X'41a7ed1ba26217cf70059964c74665d0a9c364a4078f69a3ca6d1e2623b0679f'  ,X'3f048d92be9bf806374a1823d99cfdbe1c59fbcbdf1de40001f4e39df523a4e7'   ,null                                   ,null    ),
(TRUE   ,1060          ,1001           ,10000              ,2500               ,0                          ,3500               ,0              ,null                                                                 ,null                                                                  ,null                                   ,null    ),
(TRUE   ,1070          ,1001           ,10000              ,3500               ,1                          ,null               ,0              ,null                                                                 ,null                                                                  ,null                                   ,null    ),
;

insert into shuffling_data
(db_id, shuffling_id, account_id   , data                                                                   , transaction_timestamp, height) VALUES
(1000 , 50000       , 1500         ,(X'30a93d63de4e418e858b8a8d2457001af3ae45e11eba8ab94b21651ca13cc0d8',)     , 800                  , 1001),
;