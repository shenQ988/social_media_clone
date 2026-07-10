import React from "react";
import { Navigate, useLocation, Outlet } from "react-router-dom";
import { useAuth } from "./AuthContext";

export default function ProtectedRoute() {
  const { logname, loading } = useAuth();
  const location = useLocation();

  if (loading) {
    return <div>Loading...</div>;
  }

  if (!logname) {
    const target = encodeURIComponent(
      location.pathname + location.search,
    );
    return <Navigate to={`/accounts/login/?target=${target}`} replace />;
  }

  return <Outlet />;
}
