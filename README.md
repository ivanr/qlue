# Qlue v5.x (development)

Qlue is a lightweight framework for Java web applications. Its purpose is to provide a structure
in which applications can be developed with as little complexity as possible.

## Requirements

Qlue 5 targets Jakarta EE 11: **Jakarta Servlet 6.1** (Tomcat 11 or another Servlet 6.1
container) on **Java 25**. It uses the `jakarta.*` namespace throughout.

Qlue 4 and earlier were built against the `javax.*` namespace and run on Tomcat 9 and earlier.
Upgrading is a breaking change for applications: their own `javax.servlet` imports and the
`javax.servlet.error.*` request attributes have to move to `jakarta.*` too.

I decided to write Qlue probably somewhere around 2007, mostly because all other Java frameworks
for web applications were too complex and difficult to use. I just couldn't get myself to work
with any of them. I wanted something simple, but couldn't find it.

Qlue is stable and has a number of nice features, even some that are pretty difficult to find elsewhere
(e.g., automatic context-sensitive output encoding in Velocity templates). On the other hand, it's not
well documented and there are virtually no examples that show how it can be used.

Use at your own risk. No warranties or guarantees, implied or otherwise.
