import React from "react";
import { useParams } from "react-router-dom";
import Post from "./post";

export default function PostDetail() {
  const { postid } = useParams();
  return <Post url={`/api/v1/posts/${postid}/`} />;
}
