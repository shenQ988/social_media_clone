import React, { useState } from "react";
import { Link, useNavigate } from "react-router-dom";

export default function EditPassword() {
  const navigate = useNavigate();
  const [password, setPassword] = useState("");
  const [newPassword1, setNewPassword1] = useState("");
  const [newPassword2, setNewPassword2] = useState("");
  const [error, setError] = useState(null);

  function handleSubmit(event) {
    event.preventDefault();
    setError(null);

    fetch("/api/v1/accounts/password/", {
      method: "PUT",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        password,
        new_password1: newPassword1,
        new_password2: newPassword2,
      }),
    })
      .then((response) => {
        if (response.status === 403) throw Error("Incorrect current password");
        if (!response.ok) throw Error("New passwords must match");
        navigate("/accounts/edit/");
      })
      .catch((err) => setError(err.message));
  }

  return (
    <div className="auth-page">
      <div className="card">
        <h2 className="card-title">Change password</h2>
        <form onSubmit={handleSubmit}>
          <input
            type="password"
            placeholder="current password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            required
          />
          <input
            type="password"
            placeholder="new password"
            value={newPassword1}
            onChange={(e) => setNewPassword1(e.target.value)}
            required
          />
          <input
            type="password"
            placeholder="confirm new password"
            value={newPassword2}
            onChange={(e) => setNewPassword2(e.target.value)}
            required
          />
          <input type="submit" value="submit" />
        </form>
        {error && <p className="error">{error}</p>}

        <p className="card-footer">
          <Link to="/accounts/edit/">Back to edit account</Link>
        </p>
      </div>
    </div>
  );
}
