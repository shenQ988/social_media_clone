-- Postgres schema (previously SQLite; converted for the Spring Boot backend).
-- Foreign keys are enforced natively by Postgres, no PRAGMA needed.

CREATE TABLE users(
    username    VARCHAR(20) NOT NULL,
    fullname    VARCHAR(40) NOT NULL,
    email       VARCHAR(40) NOT NULL,
    filename    VARCHAR(64) NOT NULL,
    password    VARCHAR(256) NOT NULL,
    created     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(username)
);

CREATE TABLE posts(
    postid      SERIAL      PRIMARY KEY,
    filename    VARCHAR(64) NOT NULL,
    owner       VARCHAR(20) NOT NULL,
    created     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(owner) REFERENCES users(username) ON DELETE CASCADE
);

CREATE TABLE following(
    follower    VARCHAR(20) NOT NULL,
    followee    VARCHAR(20) NOT NULL,
    created     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(follower, followee),
    FOREIGN KEY(follower) REFERENCES users(username) ON DELETE CASCADE,
    FOREIGN KEY(followee) REFERENCES users(username) ON DELETE CASCADE
);

CREATE TABLE comments(
    commentid   SERIAL PRIMARY KEY,
    owner       VARCHAR(20) NOT NULL,
    postid      INTEGER     NOT NULL,
    text        VARCHAR(1024)   NOT NULL,
    created     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(owner) REFERENCES users(username) ON DELETE CASCADE,
    FOREIGN KEY(postid) REFERENCES posts(postid) ON DELETE CASCADE
);

CREATE TABLE likes(
    likeid      SERIAL PRIMARY KEY,
    owner       VARCHAR(20) NOT NULL,
    postid      INTEGER     NOT NULL,
    created     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY(owner) REFERENCES users(username) ON DELETE CASCADE,
    FOREIGN KEY(postid) REFERENCES posts(postid) ON DELETE CASCADE
);
