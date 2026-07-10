import React, { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "./AuthContext";

export default function CreateAccount() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [fullname, setFullname] = useState("");
  const [username, setUsername] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [file, setFile] = useState(null);
  const [error, setError] = useState(null);

  function handleSubmit(event) {
    event.preventDefault();
    setError(null);

    const formData = new FormData();
    formData.append("fullname", fullname);
    formData.append("username", username);
    formData.append("email", email);
    formData.append("password", password);
    formData.append("file", file);

    fetch("/api/v1/accounts/", {
      method: "POST",
      credentials: "same-origin",
      body: formData,
    })
      .then((response) => {
        if (response.status === 409) throw Error("Username already taken");
        if (!response.ok) throw Error("Could not create account");
        return response.json();
      })
      .then((data) => {
        login(data.logname);
        navigate("/");
      })
      .catch((err) => setError(err.message));
  }

  return (
    <div className="auth-page">
      <div className="card">
        <h2 className="card-title">Sign up</h2>
        <form onSubmit={handleSubmit}>
          <input
            type="file"
            accept="image/*"
            onChange={(e) => setFile(e.target.files[0])}
            required
          />
          <input
            type="text"
            placeholder="full name"
            value={fullname}
            onChange={(e) => setFullname(e.target.value)}
            required
          />
          <input
            type="text"
            placeholder="username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            required
          />
          <input
            type="email"
            placeholder="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            required
          />
          <input
            type="password"
            placeholder="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
          <input type="submit" value="sign up" />
        </form>
        {error && <p className="error">{error}</p>}

        <p className="card-footer">
          Have an account? <Link to="/accounts/login/">Log in</Link>
        </p>
      </div>
    </div>
  );
}
