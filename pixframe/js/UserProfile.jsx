import React, { useState, useEffect } from "react";
import { Link, useParams, useNavigate } from "react-router-dom";
import { useAuth } from "./AuthContext";

export default function UserProfile() {
  const { username } = useParams();
  const { logname, logout } = useAuth();
  const navigate = useNavigate();

  const [profile, setProfile] = useState(null);
  const [notFound, setNotFound] = useState(false);
  const [newPostFile, setNewPostFile] = useState(null);

  function loadProfile() {
    fetch(`/api/v1/users/${username}/`, { credentials: "same-origin" })
      .then((response) => {
        if (response.status === 404) {
          setNotFound(true);
          return null;
        }
        if (!response.ok) throw Error(response.statusText);
        return response.json();
      })
      .then((data) => {
        if (data) setProfile(data);
      })
      .catch((error) => console.log(error));
  }

  useEffect(() => {
    setProfile(null);
    setNotFound(false);
    loadProfile();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [username]);

  function handleFollow() {
    fetch("/api/v1/following/", {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username }),
    })
      .then((response) => {
        if (!response.ok) throw Error(response.statusText);
        loadProfile();
      })
      .catch((error) => console.log(error));
  }

  function handleUnfollow() {
    fetch(`/api/v1/following/${username}/`, {
      method: "DELETE",
      credentials: "same-origin",
    })
      .then((response) => {
        if (!response.ok) throw Error(response.statusText);
        loadProfile();
      })
      .catch((error) => console.log(error));
  }

  function handleLogout(event) {
    event.preventDefault();
    logout().then(() => navigate("/accounts/login/"));
  }

  function handleUploadPost(event) {
    event.preventDefault();
    if (!newPostFile) return;

    const formData = new FormData();
    formData.append("file", newPostFile);

    fetch("/api/v1/posts/", {
      method: "POST",
      credentials: "same-origin",
      body: formData,
    })
      .then((response) => {
        if (!response.ok) throw Error(response.statusText);
        setNewPostFile(null);
        loadProfile();
      })
      .catch((error) => console.log(error));
  }

  if (notFound) {
    return <p>User not found.</p>;
  }

  if (!profile) {
    return <div>Loading...</div>;
  }

  const isOwnProfile = logname === username;

  return (
    <div className="profile-page">
      <div className="profile-header">
        <img
          src={profile.user_img_url}
          className="profile-avatar"
          alt="profile"
        />

        <div className="profile-info">
          <div className="profile-title-row">
            <h2>{profile.username}</h2>

            <div className="profile-actions">
              {isOwnProfile ? (
                <>
                  <Link to="/accounts/edit/" className="button-link">
                    Edit profile
                  </Link>
                  <form onSubmit={handleLogout}>
                    <input type="submit" value="Logout" />
                  </form>
                </>
              ) : profile.logname_follows_username ? (
                <button onClick={handleUnfollow}>unfollow</button>
              ) : (
                <button onClick={handleFollow}>follow</button>
              )}
            </div>
          </div>

          <div className="profile-stats">
            <span>
              <b>{profile.total_posts}</b> post
              {profile.total_posts !== 1 ? "s" : ""}
            </span>
            <Link to={`/users/${username}/followers/`}>
              <b>{profile.followers}</b> follower
              {profile.followers !== 1 ? "s" : ""}
            </Link>
            <Link to={`/users/${username}/following/`}>
              <b>{profile.following}</b> following
            </Link>
          </div>

          <div className="profile-fullname">{profile.fullname}</div>

          {isOwnProfile && (
            <form className="upload-post-form" onSubmit={handleUploadPost}>
              <input
                type="file"
                accept="image/*"
                onChange={(e) => setNewPostFile(e.target.files[0])}
                required
              />
              <input type="submit" value="upload new post" />
            </form>
          )}
        </div>
      </div>

      {profile.posts.length === 0 ? (
        <p className="empty-state">No posts yet.</p>
      ) : (
        <div className="posts-grid">
          {profile.posts.map((post) => (
            <Link key={post.postid} to={post.url} className="posts-grid-item">
              <img src={post.imgUrl} alt="post" />
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
