import React, { useState, useEffect } from "react";
import { Link, useParams } from "react-router-dom";
import { useAuth } from "./AuthContext";

export default function Following() {
  const { username } = useParams();
  const { logname } = useAuth();
  const [following, setFollowing] = useState(null);

  function load() {
    fetch(`/api/v1/users/${username}/following/`, {
      credentials: "same-origin",
    })
      .then((response) => response.json())
      .then((data) => setFollowing(data.following));
  }

  useEffect(() => {
    setFollowing(null);
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [username]);

  function handleFollow(targetUsername) {
    fetch("/api/v1/following/", {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username: targetUsername }),
    }).then(() => load());
  }

  function handleUnfollow(targetUsername) {
    fetch(`/api/v1/following/${targetUsername}/`, {
      method: "DELETE",
      credentials: "same-origin",
    }).then(() => load());
  }

  if (!following) {
    return <div>Loading...</div>;
  }

  return (
    <div className="list-page">
      <h2>Following</h2>
      {following.length === 0 ? (
        <p className="empty-state">Not following anyone yet.</p>
      ) : (
        <div className="user-list">
          {following.map((followee) => (
            <div key={followee.username} className="user-list-item">
              <img
                src={followee.user_img_url}
                className="profile_pic"
                alt="profile"
              />
              <Link className="user-list-name" to={`/users/${followee.username}/`}>
                {followee.username}
              </Link>
              {followee.username !== logname &&
                (followee.logname_follows_username ? (
                  <button onClick={() => handleUnfollow(followee.username)}>
                    unfollow
                  </button>
                ) : (
                  <button onClick={() => handleFollow(followee.username)}>
                    follow
                  </button>
                ))}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
