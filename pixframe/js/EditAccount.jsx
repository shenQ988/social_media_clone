import React, { useState, useEffect } from "react";
import { Link } from "react-router-dom";

export default function EditAccount() {
  const [fullname, setFullname] = useState("");
  const [email, setEmail] = useState("");
  const [file, setFile] = useState(null);
  const [userImgUrl, setUserImgUrl] = useState("");
  const [loaded, setLoaded] = useState(false);
  const [message, setMessage] = useState(null);

  useEffect(() => {
    fetch("/api/v1/accounts/", { credentials: "same-origin" })
      .then((response) => response.json())
      .then((data) => {
        setFullname(data.fullname);
        setEmail(data.email);
        setUserImgUrl(data.user_img_url);
        setLoaded(true);
      });
  }, []);

  function handleSubmit(event) {
    event.preventDefault();
    setMessage(null);

    const formData = new FormData();
    formData.append("fullname", fullname);
    formData.append("email", email);
    if (file) {
      formData.append("file", file);
    }

    fetch("/api/v1/accounts/", {
      method: "PATCH",
      credentials: "same-origin",
      body: formData,
    })
      .then((response) => {
        if (!response.ok) throw Error("Could not update account");
        return response.json();
      })
      .then((data) => {
        setUserImgUrl(`/uploads/${data.filename}`);
        setMessage("Profile updated");
      })
      .catch((err) => setMessage(err.message));
  }

  if (!loaded) {
    return <div>Loading...</div>;
  }

  return (
    <div className="auth-page">
      <div className="card">
        <h2 className="card-title">Edit profile</h2>
        <div className="avatar-preview-wrap">
          <img className="avatar-preview" src={userImgUrl} alt="profile" />
        </div>

        <form onSubmit={handleSubmit}>
          <input
            type="file"
            accept="image/*"
            onChange={(e) => setFile(e.target.files[0])}
          />
          <input
            type="text"
            value={fullname}
            onChange={(e) => setFullname(e.target.value)}
            required
          />
          <input
            type="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
          />
          <input type="submit" value="submit" />
        </form>
        {message && <p className="card-message">{message}</p>}

        <p className="card-footer">
          <Link to="/accounts/password/">Change password</Link>
          <br />
          <Link to="/accounts/delete/" className="danger-link">
            Delete account
          </Link>
        </p>
      </div>
    </div>
  );
}
