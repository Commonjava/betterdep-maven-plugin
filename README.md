<!-- Freeki metadata. Do not remove this section!
TITLE: README
-->
#Betterdep Plugin for Apache Maven

This plugin's initial goal was to produce better, more complete and reliable output for `tree` and `list` type diagnostics than the Maven Dependency Plugin (hence the name 'betterdep'). It does this using an alternative set of dependency graphing APIs, which also enable it to provide even more information about the project's depgraph. 

Currently, it supplies five goals:

* `tree`
* `list`
* `paths`
* `downlog`
* `diff`

In addition, none of these goals requires a current project to operate. This means you can use the `-Dfrom="g:a:v[, g:a:v]"` command-line parameter to print information about the dependency graph for any project that you can resolve from a repository. If you are working in a project directory, simply leave off the `from` parameter and betterdep will use `${reactorProjects}` instead (the current set of projects being built).

## Goal: `tree`

This goal prints the dependency graph formatted into tree-style output, much the same way `dependency:tree` works. 

One big improvement is the inclusion of BOMs and parent POMs referenced within the dependency graph, which gives a more complete view of the total list of GAVs required for a given project. These extra POMs are labeled with their usage, as are optional dependencies. 

For dependencies that cannot be resolved, the old `dependency:tree` goal would fail with an error. Instead, `betterdep:tree` prints a sub-tree format of '???' is given and the GAV is labeled as **NOT-RESOLVED**, much like the following:

    [...]
    org.foo:bar:1.2.3 [NOT-RESOLVED]
      ???
    commons-lang:commons-lang:2.5
    [...]

## Goal: `list`

This goal prints all GAVs referenced in the project's dependency graph, formatted as a de-duplicated, sorted list. 

As with `tree` above, BOMs and parent POMs are included and annotated.

## Goal: `paths`

Have you ever had problems with a missing artifact in your build, but had no idea where the artifact was referenced...where that dependency was coming from? This goal is designed to answer that question. The invocation takes the form:

    mvn betterdep:paths -Dto=org.foo:bar:1.2.3

**or**

    mvn betterdep:paths -Dfrom=org.myproj:project:1.0 -Dto=org.foo:bar:1.2.3

When it executes, the `paths` goal resolves the dependency graph for the `from` project (or the current project(s) you're building). Having done that, it filters the graph for only those paths leading from the root project(s) to your designated `to` GAV(s). If you want to find paths to/from multiple GAVs, you can comma-separate them on the command line.

The command and its output looks like this:

    $ mvn betterdep:paths \
            -Dfrom=org.commonjava.aprox.wars:aprox-savant:0.9.1 
            -Dto=org.commonjava.maven.atlas:atlas-identities:0.9.6

    [...]
    [INFO] Found 9 paths:
    
    org.commonjava.aprox.wars:aprox-savant:0.9.1
      org.commonjava.aprox:aprox-depgraph:jar:0.9.1
        org.commonjava.maven.cartographer:cartographer:jar:0.3.4.1
          org.commonjava.maven.atlas:atlas-identities:jar:0.9.6
    org.commonjava.aprox.wars:aprox-savant:0.9.1
      org.commonjava.aprox:aprox-depgraph:jar:0.9.1
        org.commonjava.maven.galley:galley-maven:jar:0.3.3.1
          org.commonjava.maven.atlas:atlas-identities:jar:0.9.6
    org.commonjava.aprox.wars:aprox-savant:0.9.1
      org.commonjava.aprox:aprox-core:jar:0.9.1
        org.commonjava.aprox:aprox-subsys-http:jar:0.9.1
          org.commonjava.maven.galley:galley-transport-httpclient:jar:0.3.3.1
            org.commonjava.maven.atlas:atlas-identities:jar:0.9.6

## Goal: `downlog`

The use case for this goal is a bit more obscure. In order to setup cleanroom build environments for Maven builds, some organizations have taken to parsing the captured console output from Maven builds in order to find the `Downloading...` lines, then using these as input URLs to an artifact downloader that can be used to seed the new environment.

However, running a full Maven build just to get the console output can be a pain, especially if there are a lot of tests or environment-specific configurations. The `downlog` goal resolves one or more projects' dependency graphs, then outputs the list of GAVs formatted as if it were a very condensed Maven build log...with the URL to each POM, jar, and other artifact preceded by `Downloading: `. Not only does this allow the you to avoid building the project, but you don't even have to have a copy of the project source code!

The command and its output look like this:

    $ mvn betterdep:downlog -Dfrom=org.commonjava.maven.atlas:atlas-driver-neo4j:0.9.6

    [...]
    Downloading: http://localhost:9080/aprox/api/1.0/group/public/antlr/antlr/2.7.2/antlr-2.7.2-sources.jar
    Downloading: http://localhost:9080/aprox/api/1.0/group/public/antlr/antlr/2.7.2/antlr-2.7.2-sources.jar.md5
    Downloading: http://localhost:9080/aprox/api/1.0/group/public/antlr/antlr/2.7.2/antlr-2.7.2-sources.jar.sha1
    Downloading: http://localhost:9080/aprox/api/1.0/group/public/antlr/antlr/2.7.2/antlr-2.7.2.jar
    Downloading: http://localhost:9080/aprox/api/1.0/group/public/antlr/antlr/2.7.2/antlr-2.7.2.jar.md5
    Downloading: http://localhost:9080/aprox/api/1.0/group/public/antlr/antlr/2.7.2/antlr-2.7.2.jar.sha1
    Downloading: http://localhost:9080/aprox/api/1.0/group/public/antlr/antlr/2.7.2/antlr-2.7.2.pom
    Downloading: http://localhost:9080/aprox/api/1.0/group/public/antlr/antlr/2.7.2/antlr-2.7.2.pom.md5
    Downloading: http://localhost:9080/aprox/api/1.0/group/public/antlr/antlr/2.7.2/antlr-2.7.2.pom.sha1
    Downloading: http://localhost:9080/aprox/api/1.0/group/public/ch/qos/logback/logback-classic/0.9.30/logback-classic-0.9.30-javadoc.jar
    Downloading: http://localhost:9080/aprox/api/1.0/group/public/ch/qos/logback/logback-classic/0.9.30/logback-classic-0.9.30-javadoc.jar.md5
    Downloading: http://localhost:9080/aprox/api/1.0/group/public/ch/qos/logback/logback-classic/0.9.30/logback-classic-0.9.30-javadoc.jar.sha1
    Downloading: http://localhost:9080/aprox/api/1.0/group/public/ch/qos/logback/logback-classic/0.9.30/logback-classic-0.9.30-sources.jar
    Downloading: http://localhost:9080/aprox/api/1.0/group/public/ch/qos/logback/logback-classic/0.9.30/logback-classic-0.9.30-sources.jar.md5
    Downloading: http://localhost:9080/aprox/api/1.0/group/public/ch/qos/logback/logback-classic/0.9.30/logback-classic-0.9.30-sources.jar.sha1
    Downloading: http://localhost:9080/aprox/api/1.0/group/public/ch/qos/logback/logback-classic/0.9.30/logback-classic-0.9.30.jar
    Downloading: http://localhost:9080/aprox/api/1.0/group/public/ch/qos/logback/logback-classic/0.9.30/logback-classic-0.9.30.jar.md5
    Downloading: http://localhost:9080/aprox/api/1.0/group/public/ch/qos/logback/logback-classic/0.9.30/logback-classic-0.9.30.jar.sha1
    Downloading: http://localhost:9080/aprox/api/1.0/group/public/ch/qos/logback/logback-classic/0.9.30/logback-classic-0.9.30.pom
    Downloading: http://localhost:9080/aprox/api/1.0/group/public/ch/qos/logback/logback-classic/0.9.30/logback-classic-0.9.30.pom.md5
    Downloading: http://localhost:9080/aprox/api/1.0/group/public/ch/qos/logback/logback-classic/0.9.30/logback-classic-0.9.30.pom.sha1
    [...]

**NOTE:** It's often much more useful to capture this output in a file, using the `-Doutput=output-file.txt` parameter (which is available on all of these goals).

## Goal: `diff`

Have you ever wanted to figure out what changed in the dependency graph between versions of a project?

Now you can.

This goal currently has three output formats for additions and deletions in the dependency graph:

* `full` - Print the full relationship that has changed. This includes at minimum a relationship type, declaring GAV, and target GAV (artifact, normally). For things like dependencies, it also includes the managed flag, scope, type, classifier, excludes, and any other relevant type-specific metadata.
* `brief` - Print only the form `declaring-GAV -> target-GAV[TC]` (where TC is type and classifier)
* `targets` - Print only the target GAV[TC] for each changed relationship.

Future plans for this will likely include better tree-style formatting to help understand where things are changing in the dependency graph. For now, it may be useful to use this output in conjunction with `betterdep:tree` to see where changed artifacts fit into the overall graph.
