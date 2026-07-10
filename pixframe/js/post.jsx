import React, { useState, useEffect } from "react";
import { Link, useNavigate } from "react-router-dom";
import dayjs from "dayjs";
import relativeTime from "dayjs/plugin/relativeTime";
import utc from "dayjs/plugin/utc";
import { useAuth } from "./AuthContext";

dayjs.extend(relativeTime);
dayjs.extend(utc);

// The parameter of this function is an object with a string called url inside it.
// url is a prop for the Post component.
export default function Post({ url }) {
  /* Display image and post owner of a single post */
  const { logname } = useAuth();
  const navigate = useNavigate();

  const [likes, setLikes] = useState(0); // from data.likes.numLikes
  const [userLiked, setUserLiked] = useState(false); // from data.likes.lognameLikesThis
  const [likeUrl, setLikeUrl] = useState(null); // from data.likes.url
  const [comments, setComments] = useState([]); // from data.comments

  // Static (Things that just read once)
  const [owner, setOwner] = useState("");
  const [imgUrl, setImgUrl] = useState("");
  const [timestamp, setTimestamp] = useState("");
  const [ownerPhoto, setOwnerPhoto] = useState(""); // from data.ownerImgUrl
  const [ownerUrl, setOwnerUrl] = useState(""); // from data.ownerShowUrl
  const [postUrl, setPostUrl] = useState(""); // from data.postShowUrl
  const [postId, setPostId] = useState(null);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    // Declare a boolean flag that we can use to cancel the API request.
    let ignoreStaleRequest = false;

    // Call REST API to get the post's information
    fetch(url, { credentials: "same-origin" })
      .then((response) => {
        if (!response.ok) throw Error(response.statusText);
        return response.json();
      })
      .then((data) => {
        // If ignoreStaleRequest was set to true, we want to ignore the results of the
        // the request. Otherwise, update the state to trigger a new render.
        if (!ignoreStaleRequest) {
          // 1. Interactive Data
          setLikes(data.likes.numLikes);
          setUserLiked(data.likes.lognameLikesThis);
          setLikeUrl(data.likes.url);
          setComments(data.comments);

          // 2. Static Data
          setImgUrl(data.imgUrl);
          setOwner(data.owner);
          setOwnerPhoto(data.ownerImgUrl);
          setOwnerUrl(data.ownerShowUrl);
          setTimestamp(data.created);
          setPostUrl(data.postShowUrl);
          setPostId(data.postid);
          setLoaded(true);
        }
      })
      .catch((error) => console.log(error));

    return () => {
      // This is a cleanup function that runs whenever the Post component
      // unmounts or re-renders. If a Post is about to unmount or re-render, we
      // should avoid updating state.
      ignoreStaleRequest = true;
    };
  }, [url]);

  function handleLike() {
    if (userLiked) {
      fetch(likeUrl, { method: "DELETE", credentials: "same-origin" })
        .then((response) => {
          if (!response.ok) throw Error(response.statusText);
          setUserLiked(false);
          setLikes(likes - 1);
          setLikeUrl(null);
        })
        .catch((error) => console.log(error));
    } else {
      fetch(`/api/v1/likes/?postid=${postId}`, {
        method: "POST",
        credentials: "same-origin",
      })
        .then((response) => {
          if (!response.ok) throw Error(response.statusText);
          return response.json();
        })
        .then((parsedjson) => {
          setUserLiked(true);
          setLikeUrl(parsedjson.url);
          setLikes(likes + 1);
        })
        .catch((error) => console.log(error));
    }
  }

  function handleImageDoubleClick() {
    // Only call handleLike if the user hasn't liked the post yet.
    if (!userLiked) {
      handleLike();
    }
  }

  function handleCreateComment(text) {
    fetch(`/api/v1/comments/?postid=${postId}`, {
      method: "POST",
      body: JSON.stringify({ text: text }),
      headers: { "Content-Type": "application/json" },
      credentials: "same-origin",
    })
      .then((response) => {
        if (!response.ok) throw Error(response.statusText);
        return response.json();
      })
      .then((newComment) => {
        // Update state by appending the new comment to the existing list
        setComments((prevComments) => [...prevComments, newComment]);
      })
      .catch((error) => console.log(error));
  }

  function handleDeleteComment(commentid) {
    fetch(`/api/v1/comments/${commentid}/`, {
      method: "DELETE",
      credentials: "same-origin",
    })
      .then((response) => {
        if (!response.ok) throw Error(response.statusText);
        // Update state by filtering out the deleted comment
        setComments((prevComments) =>
          prevComments.filter((comment) => comment.commentid !== commentid),
        );
      })
      .catch((error) => console.log(error));
  }

  function handleDeletePost() {
    fetch(`/api/v1/posts/${postId}/`, {
      method: "DELETE",
      credentials: "same-origin",
    })
      .then((response) => {
        if (!response.ok) throw Error(response.statusText);
        navigate(`/users/${owner}/`);
      })
      .catch((error) => console.log(error));
  }
  // Don't render data-dependent content until the fetch completes
  if (!loaded) {
    return <div className="post">Loading...</div>;
  }

  // Render post image and post owner
  return (
    <div className="post">
      {/* Header */}
      <Post_Header
        owner={owner}
        ownerImgUrl={ownerPhoto}
        timestamp={timestamp}
        postUrl={postUrl}
        ownerUrl={ownerUrl}
      />

      {/* post image */}
      <div className="post-image-wrap">
        <img
          src={imgUrl}
          alt="post_image"
          onDoubleClick={handleImageDoubleClick}
        />
      </div>

      {/* like button */}
      <div className="post-actions">
        <Like_button
          logname_like_this={userLiked}
          num_like={likes}
          handlelike={handleLike}
        />
      </div>

      {/* comments */}
      <div className="post-comments">
        <Comments
          comments_list={comments}
          handleDelete={handleDeleteComment} // Pass handler down
        />
        <CommentForm handleCreateComment={handleCreateComment} />
      </div>

      {/* delete post button, owner only */}
      {logname === owner && (
        <button
          className="delete-post-button"
          data-testid="delete-post-button"
          onClick={handleDeletePost}
        >
          delete this post
        </button>
      )}
    </div>
  );
}

// Post下面都是独立的

function Post_Header({ owner, ownerImgUrl, timestamp, postUrl, ownerUrl }) {
  const [timeString, setTimeString] = useState("");

  useEffect(() => {
    // Define the update function
    const updateTime = () => {
      setTimeString(dayjs.utc(timestamp).fromNow());
    };
    const timer = setInterval(updateTime, 60000);
    updateTime();

    return () => {
      clearInterval(timer);
    };
  }, [timestamp]); // Re-run this effect if the timestamp prop changes

  return (
    <div className="post_header">
      {/* Link the icon to the user's profile */}
      <Link to={ownerUrl}>
        <img className="post-user-icon" src={ownerImgUrl} alt={owner} />
      </Link>

      {/* Link the username to the user's profile */}
      <Link className="post-username" to={ownerUrl}>
        {owner}
      </Link>

      {/* Display timeString instead of the raw timestamp */}
      <Link to={postUrl} data-testid="post-time-ago">
        {timeString}
      </Link>
    </div>
  );
}

function Comments({ comments_list, handleDelete }) {
  return (
    <div className="comments-list">
      {comments_list.map((comment) => (
        <Single_comment
          key={comment.commentid}
          {...comment}
          handleDelete={handleDelete}
        />
      ))}
    </div>
  );
}

function Single_comment({
  commentid,
  owner,
  ownerShowUrl,
  text,
  lognameOwnsThis,
  handleDelete,
}) {
  return (
    <div className="single-comment">
      <Link className="comment-owner" to={ownerShowUrl}>
        {owner}
      </Link>
      <span data-testid="comment-text">{text}</span>

      {/* Only show delete button if the user owns the comment */}
      {lognameOwnsThis && (
        <button
          className="delete-comment-button"
          data-testid="delete-comment-button"
          onClick={() => handleDelete(commentid)}
        >
          Delete
        </button>
      )}
    </div>
  );
}

function Like_button({ logname_like_this, num_like, handlelike }) {
  return (
    <>
      <button onClick={handlelike} data-testid="like-unlike-button">
        {logname_like_this ? "unlike" : "like"}
      </button>

      <div className="like-counts">
        {num_like == 1 ? "1 like" : `${num_like} likes`}
      </div>
    </>
  );
}

function CommentForm({ handleCreateComment }) {
  const [text, setText] = useState("");

  function handleSubmit(event) {
    event.preventDefault(); // Prevent page reload
    handleCreateComment(text);
    setText(""); // Clear input after submit
  }

  return (
    <form
      className="comment-form"
      onSubmit={handleSubmit}
      data-testid="comment-form"
    >
      <input
        type="text"
        placeholder="Add a comment..."
        value={text}
        onChange={(event) => setText(event.target.value)}
      />
    </form>
  );
}
