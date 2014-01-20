<!-- Freeki metadata. Do not remove this section!
TITLE: README
-->
#Betterdep Plugin for Apache Maven

This plugin's initial goal was to produce better, more complete and reliable output for `tree` and `list` type diagnostics than the Maven Dependency Plugin (hence the name 'betterdep'). It does this using an alternative set of dependency graphing APIs, which also enable it to provide even more information about the project's depgraph. 

Currently, it supplies four goals:

* `tree`
* `list`
* `downlog`
* `paths`

In addition, none of these goals requires a current project to operate. This means you can use the `-Dfrom="g:a:v[, g:a:v]"` command-line parameter to print information about the dependency graph for any project that you can resolve from a repository. If you are working in a project directory, simply leave off the `from` parameter and betterdep will use `${reactorProjects}` instead (the current set of projects being built).

## Goal: `tree`

This goal prints the dependency graph formatted into tree-style output, much the same way `dependency:tree` works. 

One big improvement is the inclusion of BOMs and parent POMs referenced within the dependency graph, which gives a more complete view of the total list of GAVs required for a given project. These extra POMs are labeled with their usage, as are optional dependencies. 

For dependencies that cannot be resolved, the old `dependency:tree` goal would fail with an error. Instead, `betterdep:tree` prints a sub-tree format of '???' is given and the GAV is labeled as **UNRESOLVED**, much like the following:

    [...]
    org.foo:bar:1.2.3 [UNRESOLVED]
      ???
    commons-lang:commons-lang:2.5
    [...]

## Goal: `list`

This goal prints all GAVs referenced in the project's dependency graph, formatted as a de-duplicated, sorted list. 

As with `tree` above, BOMs and parent POMs are included and annotated.

