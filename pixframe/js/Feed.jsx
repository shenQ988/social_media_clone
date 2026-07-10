import React, { useState, useEffect, useCallback, useRef } from "react";
import InfiniteScroll from "react-infinite-scroll-component";
import Post from "./post";

export default function Feed() {
  const [posts, setPosts] = useState([]);
  const [nextPageUrl, setNextPageUrl] = useState("/api/v1/posts/");
  const [hasMorePosts, setHasMorePosts] = useState(true);
  // will not re render when isFetching changed
  const isFetching = useRef(false);

  const fetchNextPage = useCallback(() => {
    if (isFetching.current || !nextPageUrl) {
      return;
    }
    // lock
    isFetching.current = true;

    fetch(nextPageUrl, {
      method: "GET",
      credentials: "same-origin",
    })
      .then((response) => {
        if (!response.ok) throw Error(response.statusText);
        return response.json();
      })
      .then((data) => {
        setPosts((prevPosts) => [...prevPosts, ...data.results]);
        setNextPageUrl(data.next);

        if (!data.next) {
          setHasMorePosts(false);
        }
        isFetching.current = false;
      })
      .catch((error) => {
        console.log(error);
        // Release lock
        isFetching.current = false;
      });
  }, [nextPageUrl]);

  // Initial load
  useEffect(() => {
    fetchNextPage();
  }, [fetchNextPage]);

  return (
    <div className="feed">
      <InfiniteScroll
        dataLength={posts.length}
        next={fetchNextPage}
        hasMore={hasMorePosts}
        loader={<p className="feed-loader">Loading...</p>}
        endMessage={<p className="feed-end">Yay! You have seen it all</p>}
      >
        {/* Render the list of Post components */}
        {posts.map((post) => (
          <Post key={post.postid} url={post.url} />
        ))}
      </InfiniteScroll>
    </div>
  );
}
