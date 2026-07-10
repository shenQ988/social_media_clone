import React, {
  createContext,
  useContext,
  useState,
  useEffect,
  useCallback,
} from "react";

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [logname, setLogname] = useState(null);
  const [loading, setLoading] = useState(true);

  const refreshAuth = useCallback(() => {
    return fetch("/api/v1/accounts/auth/", { credentials: "same-origin" })
      .then((response) => {
        if (!response.ok) {
          setLogname(null);
          return;
        }
        return response.json().then((data) => setLogname(data.logname));
      })
      .catch(() => setLogname(null))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    refreshAuth();
  }, [refreshAuth]);

  function login(username) {
    setLogname(username);
  }

  function logout() {
    return fetch("/api/v1/accounts/logout/", {
      method: "POST",
      credentials: "same-origin",
    }).finally(() => setLogname(null));
  }

  return (
    <AuthContext.Provider value={{ logname, loading, login, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  return useContext(AuthContext);
}
