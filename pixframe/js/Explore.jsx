import React, { useState, useEffect } from "react";
import { Link } from "react-router-dom";

export default function Explore() {
  const [notFollowing, setNotFollowing] = useState(null);

  function load() {
    fetch("/api/v1/explore/", { credentials: "same-origin" })
      .then((response) => response.json())
      .then((data) => setNotFollowing(data.results));
  }

  useEffect(() => {
    load();
  }, []);

  function handleFollow(username) {
    fetch("/api/v1/following/", {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username }),
    }).then(() => load());
  }

  if (!notFollowing) {
    return <div>Loading...</div>;
  }

  return (
    <div className="list-page">
      <h2>Explore</h2>
      {notFollowing.length === 0 ? (
        <p className="empty-state">You&apos;re following everyone already.</p>
      ) : (
        <div className="user-list">
          {notFollowing.map((user) => (
            <div key={user.username} className="user-list-item">
              <img
                src={user.user_img_url}
                className="profile_pic"
                alt="profile"
              />
              <Link className="user-list-name" to={`/users/${user.username}/`}>
                {user.username}
              </Link>
              <button onClick={() => handleFollow(user.username)}>
                follow
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
