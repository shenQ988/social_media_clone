-- Postgres seed data (previously SQLite; converted for the Spring Boot backend).

-- Users
-- NOTE: all seed accounts use the password "password123" for local testing.
INSERT INTO users (username, fullname, email, filename, password)
VALUES ('mkim', 'Maya Kim', 'maya.kim@example.com', '4af7ed2cff8e4e4480178acea9c16f7e.png', 'sha512$1ee868ebef9543d1bcf4095312cf142d$7450ebce7c78c962a770b3673d58d6f9a54b0ba445c25ed69bceb8b6b2e809c69c5ebe6d9c8a55a57f5ca4bcca02aaf95934f48745a7532f6e9f379513a51696');

INSERT INTO users (username, fullname, email, filename, password)
VALUES ('dsingh', 'Dev Singh', 'dev.singh@example.com', 'e7fcfea4a2d445d18a4788858c118b78.png', 'sha512$97e3c13de537486cb8f25e936390ee87$549babe0872067d98db08733d2e77daf26d5713d2b96e930fdf1640efe49bf84864b40de4e2cfbbf008065eba3a6a9a26b67363a2904b646e13a24e8debdb510');

INSERT INTO users (username, fullname, email, filename, password)
VALUES ('lchen', 'Lena Chen', 'lena.chen@example.com', 'bca3e8b919964016a47e487d98b0be0f.png', 'sha512$e1668970fbad4677a94f150bf9e9836b$651d6c9415a8e2c47a516f323fa4d319da240c9e07072e4d243f850698620a3a2936769412e5d0ad6cda574b99e0241d90e61a563de127c11ddedcaffcff0c08');

INSERT INTO users (username, fullname, email, filename, password)
VALUES ('rortiz', 'Rafael Ortiz', 'rafael.ortiz@example.com', '0941a1afa77f4b35a5c6f88c4b1e43af.png', 'sha512$d3e4b24782774f81ba83e159bf441159$27ddf0178c7a14d34adce15263c2b3c31a228501ad7b933f6ab7eb955a5c42ede9d1290bfb4352f3b9e55742dc0d149c6449bc257d3046153cbe0157b1199a24');

-- Posts
INSERT INTO posts (postid, filename, owner)
VALUES (1, '9b897c561bac48b0896a31d0f26de616.png', 'mkim');

INSERT INTO posts (postid, filename, owner)
VALUES (2, '7991bfb47dc7463c88faddb947ae04f5.png', 'dsingh');

INSERT INTO posts (postid, filename, owner)
VALUES (3, '757acff6790c4b9ea7b5725396cdbc7f.png', 'mkim');

INSERT INTO posts (postid, filename, owner)
VALUES (4, '6159eeeacffa45799ccb6f6015df8a49.png', 'rortiz');

INSERT INTO posts (postid, filename, owner)
VALUES (5, '499bdc5c7e204d91b7e9571cb25d381b.png', 'mkim');

INSERT INTO posts (postid, filename, owner)
VALUES (6, '49f1e88a01024d09af4fda3a5d65ccfa.png', 'mkim');

INSERT INTO posts (postid, filename, owner)
VALUES (7, 'e3fbcede0ba44b8ab4a58fa98c5ffc85.png', 'dsingh');

INSERT INTO posts (postid, filename, owner)
VALUES (8, 'ef958983c7494635ba1d1e835f823e35.png', 'dsingh');

INSERT INTO posts (postid, filename, owner)
VALUES (9, '9b897c561bac48b0896a31d0f26de616.png', 'lchen');

INSERT INTO posts (postid, filename, owner)
VALUES (10, '7991bfb47dc7463c88faddb947ae04f5.png', 'lchen');

INSERT INTO posts (postid, filename, owner)
VALUES (11, '757acff6790c4b9ea7b5725396cdbc7f.png', 'rortiz');

INSERT INTO posts (postid, filename, owner)
VALUES (12, '6159eeeacffa45799ccb6f6015df8a49.png', 'rortiz');

-- Following
INSERT INTO following (follower, followee)
VALUES ('mkim', 'dsingh');

INSERT INTO following (follower, followee)
VALUES ('mkim', 'lchen');

INSERT INTO following (follower, followee)
VALUES ('dsingh', 'mkim');

INSERT INTO following (follower, followee)
VALUES ('dsingh', 'lchen');

INSERT INTO following (follower, followee)
VALUES ('lchen', 'mkim');

INSERT INTO following (follower, followee)
VALUES ('lchen', 'rortiz');

INSERT INTO following (follower, followee)
VALUES ('rortiz', 'lchen');

-- Comments
INSERT INTO comments (commentid, owner, postid, text)
VALUES (1, 'mkim', 3, '#backyardchickens');

INSERT INTO comments (commentid, owner, postid, text)
VALUES (2, 'dsingh', 3, 'love these little guys');

INSERT INTO comments (commentid, owner, postid, text)
VALUES (3, 'lchen', 3, 'cute overload!');

INSERT INTO comments (commentid, owner, postid, text)
VALUES (4, 'mkim', 2, 'nice crossword');

INSERT INTO comments (commentid, owner, postid, text)
VALUES (5, 'dsingh', 1, 'walking the plank');

INSERT INTO comments (commentid, owner, postid, text)
VALUES (6, 'mkim', 1, 'this was after trying to teach them a crossword');

INSERT INTO comments (commentid, owner, postid, text)
VALUES (7, 'rortiz', 4, 'saw this downtown yesterday!');

INSERT INTO comments (commentid, owner, postid, text)
VALUES (8, 'dsingh', 5, '#morninghike what a view');

INSERT INTO comments (commentid, owner, postid, text)
VALUES (9, 'lchen', 5, 'need to join you next time');

INSERT INTO comments (commentid, owner, postid, text)
VALUES (10, 'mkim', 7, 'those look comfy');

INSERT INTO comments (commentid, owner, postid, text)
VALUES (11, 'lchen', 8, 'what did you end up building?');

INSERT INTO comments (commentid, owner, postid, text)
VALUES (12, 'mkim', 9, 'gorgeous shot');

INSERT INTO comments (commentid, owner, postid, text)
VALUES (13, 'rortiz', 10, 'best view in the city');

INSERT INTO comments (commentid, owner, postid, text)
VALUES (14, 'lchen', 11, 'recipe please!');

-- Likes
INSERT INTO likes (likeid, owner, postid)
VALUES (1, 'mkim', 1);

INSERT INTO likes (likeid, owner, postid)
VALUES (2, 'lchen', 1);

INSERT INTO likes (likeid, owner, postid)
VALUES (3, 'dsingh', 1);

INSERT INTO likes (likeid, owner, postid)
VALUES (4, 'mkim', 2);

INSERT INTO likes (likeid, owner, postid)
VALUES (5, 'lchen', 2);

INSERT INTO likes (likeid, owner, postid)
VALUES (6, 'mkim', 3);

INSERT INTO likes (likeid, owner, postid)
VALUES (7, 'dsingh', 5);

INSERT INTO likes (likeid, owner, postid)
VALUES (8, 'lchen', 5);

INSERT INTO likes (likeid, owner, postid)
VALUES (9, 'rortiz', 6);

INSERT INTO likes (likeid, owner, postid)
VALUES (10, 'mkim', 7);

INSERT INTO likes (likeid, owner, postid)
VALUES (11, 'lchen', 8);

INSERT INTO likes (likeid, owner, postid)
VALUES (12, 'mkim', 9);

INSERT INTO likes (likeid, owner, postid)
VALUES (13, 'dsingh', 9);

INSERT INTO likes (likeid, owner, postid)
VALUES (14, 'rortiz', 10);

INSERT INTO likes (likeid, owner, postid)
VALUES (15, 'lchen', 11);

INSERT INTO likes (likeid, owner, postid)
VALUES (16, 'mkim', 12);

-- Since rows above were inserted with explicit ids, advance the SERIAL
-- sequences so the next auto-generated id doesn't collide with these.
SELECT setval('posts_postid_seq', (SELECT MAX(postid) FROM posts));
SELECT setval('comments_commentid_seq', (SELECT MAX(commentid) FROM comments));
SELECT setval('likes_likeid_seq', (SELECT MAX(likeid) FROM likes));
