-- Postgres seed data (previously SQLite; converted for the Spring Boot backend).

-- Users
-- NOTE: all seed accounts use the password "password123" for local testing.
INSERT INTO users (username, fullname, email, filename, password)
VALUES ('mkim', 'Maya Kim', 'maya.kim@example.com', '736762ebb1db4998ac5c3bc224833b36.jpg', 'sha512$1ee868ebef9543d1bcf4095312cf142d$7450ebce7c78c962a770b3673d58d6f9a54b0ba445c25ed69bceb8b6b2e809c69c5ebe6d9c8a55a57f5ca4bcca02aaf95934f48745a7532f6e9f379513a51696');

INSERT INTO users (username, fullname, email, filename, password)
VALUES ('dsingh', 'Dev Singh', 'dev.singh@example.com', '671cbbed51e844bb89115a1242b7a457.jpg', 'sha512$97e3c13de537486cb8f25e936390ee87$549babe0872067d98db08733d2e77daf26d5713d2b96e930fdf1640efe49bf84864b40de4e2cfbbf008065eba3a6a9a26b67363a2904b646e13a24e8debdb510');

INSERT INTO users (username, fullname, email, filename, password)
VALUES ('lchen', 'Lena Chen', 'lena.chen@example.com', 'f581ce53b7e24a2cb2ae6f0ab67cfe0f.jpg', 'sha512$e1668970fbad4677a94f150bf9e9836b$651d6c9415a8e2c47a516f323fa4d319da240c9e07072e4d243f850698620a3a2936769412e5d0ad6cda574b99e0241d90e61a563de127c11ddedcaffcff0c08');

INSERT INTO users (username, fullname, email, filename, password)
VALUES ('rortiz', 'Rafael Ortiz', 'rafael.ortiz@example.com', 'e961530a297c4dbcada9e2858b34e787.jpg', 'sha512$d3e4b24782774f81ba83e159bf441159$27ddf0178c7a14d34adce15263c2b3c31a228501ad7b933f6ab7eb955a5c42ede9d1290bfb4352f3b9e55742dc0d149c6449bc257d3046153cbe0157b1199a24');

-- Posts
INSERT INTO posts (postid, filename, owner)
VALUES (1, '87f47b27d120408087a86b9c4ad248e5.jpg', 'mkim');

INSERT INTO posts (postid, filename, owner)
VALUES (2, 'cf9c3791c80943b3abdbf7dcab7d30b7.jpg', 'dsingh');

INSERT INTO posts (postid, filename, owner)
VALUES (3, '242912decd914b58817bd1ac6784be04.jpg', 'mkim');

INSERT INTO posts (postid, filename, owner)
VALUES (4, '78e62ad68c7441a693d9aad44101035d.jpg', 'rortiz');

INSERT INTO posts (postid, filename, owner)
VALUES (5, 'a0ae8e91dbcd47c19dafcae7fafa558d.jpg', 'mkim');

INSERT INTO posts (postid, filename, owner)
VALUES (6, '2629f6ae6af94fadaa4cbc37495d7c3d.jpg', 'mkim');

INSERT INTO posts (postid, filename, owner)
VALUES (7, '16e051c0b46043f79c3d86d2e0521c47.jpg', 'dsingh');

INSERT INTO posts (postid, filename, owner)
VALUES (8, 'cb993934ea2845deb8f7246e2c1708de.jpg', 'dsingh');

INSERT INTO posts (postid, filename, owner)
VALUES (9, '0d80e174d7be49289b9d2ba729dc9107.jpg', 'lchen');

INSERT INTO posts (postid, filename, owner)
VALUES (10, 'd6b0583d1cfe47c583a78022ddca9ead.jpg', 'lchen');

INSERT INTO posts (postid, filename, owner)
VALUES (11, '405b42d171c4468aa2dc89af6f236789.jpg', 'rortiz');

INSERT INTO posts (postid, filename, owner)
VALUES (12, '21e905327fbc4025ac647d13225b25cb.jpg', 'rortiz');

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
