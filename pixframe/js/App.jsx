import React from "react";
import { BrowserRouter, Routes, Route } from "react-router-dom";
import { AuthProvider } from "./AuthContext";
import ProtectedRoute from "./ProtectedRoute";
import Layout from "./Layout";
import Feed from "./Feed";
import Login from "./Login";
import CreateAccount from "./CreateAccount";
import EditAccount from "./EditAccount";
import EditPassword from "./EditPassword";
import DeleteAccount from "./DeleteAccount";
import UserProfile from "./UserProfile";
import Followers from "./Followers";
import Following from "./Following";
import Explore from "./Explore";
import PostDetail from "./PostDetail";

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/accounts/login/" element={<Login />} />
            <Route path="/accounts/create/" element={<CreateAccount />} />

            <Route element={<ProtectedRoute />}>
              <Route path="/" element={<Feed />} />
              <Route path="/accounts/edit/" element={<EditAccount />} />
              <Route path="/accounts/password/" element={<EditPassword />} />
              <Route path="/accounts/delete/" element={<DeleteAccount />} />
              <Route path="/users/:username/" element={<UserProfile />} />
              <Route
                path="/users/:username/followers/"
                element={<Followers />}
              />
              <Route
                path="/users/:username/following/"
                element={<Following />}
              />
              <Route path="/explore/" element={<Explore />} />
              <Route path="/posts/:postid/" element={<PostDetail />} />
            </Route>
          </Route>
        </Routes>
      </AuthProvider>
    </BrowserRouter>
  );
}
