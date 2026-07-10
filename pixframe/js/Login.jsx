import React, { useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { useAuth } from "./AuthContext";

export default function Login() {
  const { login } = useAuth();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState(null);

  function handleSubmit(event) {
    event.preventDefault();
    setError(null);

    fetch("/api/v1/accounts/login/", {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ username, password }),
    })
      .then((response) => {
        if (!response.ok) throw Error("Incorrect username or password");
        return response.json();
      })
      .then((data) => {
        login(data.logname);
        navigate(searchParams.get("target") || "/");
      })
      .catch((err) => setError(err.message));
  }

  return (
    <div className="auth-page">
      <div className="card">
        <h2 className="card-title">Log in</h2>
        <form onSubmit={handleSubmit}>
          <input
            type="text"
            placeholder="username"
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            required
          />
          <input
            type="password"
            placeholder="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
          <input type="submit" value="login" />
        </form>
        {error && <p className="error">{error}</p>}

        <p className="card-footer">
          Don&apos;t have an account? <Link to="/accounts/create/">Sign up</Link>
        </p>
      </div>
    </div>
  );
}
