FS2: The Reader
=============

This is a fork that is designed to create a fully annotated snapshot of FS2 source code with as much text explaining what is going on as possible. 

# Notes

[FreeC](https://github.com/jartur/fs2/blob/series/1.0/core/shared/src/main/scala/fs2/internal/FreeC.scala) is the local implementation of a special flavour of Free Monad.

[Algebra](https://github.com/jartur/fs2/blob/series/1.0/core/shared/src/main/scala/fs2/internal/Algebra.scala) is, probably, some kind of a base algebra of which Stream is an implementation? It uses `FreeC` in some way.
