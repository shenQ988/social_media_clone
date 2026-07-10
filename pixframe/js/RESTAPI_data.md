Sample API result from requesting GET /api/v1/posts/<postid>/, which is getting the detail of a post
{
"comments": [
{
"commentid": 1,
"lognameOwnsThis": true,
"owner": "awdeorio",
"ownerShowUrl": "/users/awdeorio/",
"text": "#chickensofinstagram",
"url": "/api/v1/comments/1/"
},
{
"commentid": 2,
"lognameOwnsThis": false,
"owner": "jflinn",
"ownerShowUrl": "/users/jflinn/",
"text": "I <3 chickens",
"url": "/api/v1/comments/2/"
}
],
"comments_url": "/api/v1/comments/?postid=3",
"created": "2021-05-06 19:52:44",
"imgUrl": "/uploads/9887e06812ef434d291e4936417d125cd594b38a.jpg",
"likes": {
"lognameLikesThis": true,
"numLikes": 1,
"url": "/api/v1/likes/6/"
},
"owner": "awdeorio",
"ownerImgUrl": "/uploads/e1a7c5c32973862ee15173b0259e3efdb6a391af.jpg",
"ownerShowUrl": "/users/awdeorio/",
"postShowUrl": "/posts/3/",
"postid": 3,
"url": "/api/v1/posts/3/"
}

Sample REST API response for GET /api/v1/posts/ , these returns the info for the posts should display

{
"next": "",
"results": [
{
"postid": 3,
"url": "/api/v1/posts/3/"
},
{
"postid": 2,
"url": "/api/v1/posts/2/"
}
],
"url": "/api/v1/posts/"
}

Delete like will return 204 if sucess, and no content

Create like will return the likeid we just make, and the url for that like.
Create a Like will return:

{
"likeid": 6,
"url": "/api/v1/likes/6/"
}
