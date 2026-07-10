import React from "react";
import { Link, Outlet } from "react-router-dom";
import { useAuth } from "./AuthContext";

export default function Layout() {
  const { logname } = useAuth();

  return (
    <div className="app-shell">
      <div className="top-bar">
        <h1 className="brand">
          <Link to="/">
            <img
              src="/static/images/pixframe_logo.png"
              alt="Pixframe logo"
              className="logo"
            />
            Pixframe
          </Link>
        </h1>

        <h2 className="nav-links">
          {logname ? (
            <>
              <Link to="/explore/">explore</Link>
              <Link to={`/users/${logname}/`}>{logname}</Link>
            </>
          ) : (
            <Link to="/accounts/login/">login</Link>
          )}
        </h2>
      </div>
      <main className="page-content">
        <Outlet />
      </main>
    </div>
  );
}
