import React, { useState, useEffect } from "react";
import { Link, useParams } from "react-router-dom";
import { useAuth } from "./AuthContext";

export default function Followers() {
  const { username } = useParams();
  const { logname } = useAuth();
  const [followers, setFollowers] = useState(null);

  function load() {
    fetch(`/api/v1/users/${username}/followers/`, {
      credentials: "same-origin",
    })
      .then((response) => response.json())
      .then((data) => setFollowers(data.followers));
  }

  useEffect(() => {
    setFollowers(null);
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

  if (!followers) {
    return <div>Loading...</div>;
  }

  return (
    <div className="list-page">
      <h2>Followers</h2>
      {followers.length === 0 ? (
        <p className="empty-state">No followers yet.</p>
      ) : (
        <div className="user-list">
          {followers.map((follower) => (
            <div key={follower.username} className="user-list-item">
              <img
                src={follower.user_img_url}
                className="profile_pic"
                alt="profile"
              />
              <Link className="user-list-name" to={`/users/${follower.username}/`}>
                {follower.username}
              </Link>
              {follower.username !== logname &&
                (follower.logname_follows_username ? (
                  <button onClick={() => handleUnfollow(follower.username)}>
                    unfollow
                  </button>
                ) : (
                  <button onClick={() => handleFollow(follower.username)}>
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
