# jms4s Request-Reply Library

This library adds support for the [Request-Reply Messaging Pattern](https://www.enterpriseintegrationpatterns.com/RequestReply.html) to [jms4s](https://fpinbo.dev/jms4s/), a functional wrapper for JMS.

**Important note**
Currently this project is configured to depend on custom build of jms4s which is available as a GitHub package from https://github.com/rwalpole/jms4s

To access GitHub packages you will need to have a [GitHub Personal Access Token](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/creating-a-personal-access-token) set either as an environment variable called `GITHUB_TOKEN` or by configuring `~/.gitconfig` as follows:-
```
[user]
    name = [YOUR NAME]
    email = [YOUR EMAIL]
[github]
    token = [YOUR GITHUB PERSONAL ACCESS TOKEN]
```
To run the tests in this project you will need to run Docker with an instance of ElasticMQ which can be achieved by running the following command from the root of the project:
```
docker compose up -d
```
Once ElasticMQ is up you will also need to run the `EchoServer` stub service [from this project](https://github.com/nationalarchives/jms4s-request-reply-stub) which is configured to listen to the same message broker and respond to the test messages.

### Pre-requisites for building and running the project:
* [Git](https://git-scm.com)
* [Docker](https://docs.docker.com/get-docker/)
* [sbt](https://www.scala-sbt.org/) >= 1.6.2
* [Scala](https://www.scala-lang.org/) >= 2.13.8
* [Java JDK](https://adoptopenjdk.net/) >= 1.8