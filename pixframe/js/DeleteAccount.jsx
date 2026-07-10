import React from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "./AuthContext";

export default function DeleteAccount() {
  const { logname, logout } = useAuth();
  const navigate = useNavigate();

  function handleDelete(event) {
    event.preventDefault();
    fetch("/api/v1/accounts/", {
      method: "DELETE",
      credentials: "same-origin",
    })
      .then((response) => {
        if (!response.ok) throw Error("Could not delete account");
        return logout();
      })
      .then(() => navigate("/accounts/login/"))
      .catch((err) => console.log(err));
  }

  return (
    <div className="auth-page">
      <div className="card">
        <h2 className="card-title">Delete account</h2>
        <p>
          Are you sure you want to permanently delete <b>{logname}</b>?
        </p>
        <form onSubmit={handleDelete}>
          <input
            className="danger-button"
            type="submit"
            value="confirm delete account"
          />
        </form>
      </div>
    </div>
  );
}
