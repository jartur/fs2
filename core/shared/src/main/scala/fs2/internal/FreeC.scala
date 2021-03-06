package fs2.internal

import cats.{Monad, MonadError, ~>}
import cats.effect.{ExitCase, Sync}
import fs2.CompositeFailure
import FreeC._

import scala.annotation.tailrec
import scala.util.control.NonFatal

/**
  * Free Monad with Catch (and Interruption).
  *
  * [[FreeC]] provides mechanism for ensuring stack safety and capturing any exceptions that may arise during computation.
  *
  * Furthermore, it may capture Interruption of the evaluation, although [[FreeC]] itself does not have any
  * interruptible behaviour per se.
  *
  * Interruption cause may be captured in [[FreeC.Result.Interrupted]] and allows user to pass along any information relevant
  * to interpreter.
  *
  * Typically the [[FreeC]] user provides interpretation of FreeC in form of [[ViewL]] structure, that allows to step
  * FreeC via series of Results ([[Result.Pure]], [[Result.Fail]] and [[Result.Interrupted]]) and FreeC step ([[ViewL.View]])
  *
  *
  */


// A simplified overview of types

// data FreeC f r =
//   | Pure r
//   | Fail Throwable
//   | Interrupted ctx (Maybe Throwable)
//   | Eval (f r)
//   | Bind (FreeC f a) (Result a -> FreeC f r)

// data Result r = Pure r | Fail Throwable | Interrupted ctx (Maybe Throwable)
// data ViewL f r = Pure r | Fail Throwable | Interrupted ctx (Maybe Throwable)
//                  | View (f x) (Result x -> FreeC f r)
// Note how the same data constructors (case classes) are used to construct values of 3 different types, this is 
// possible due to subtyping and the fact that in Scala case classes do actually have their own types.
private[fs2] sealed abstract class FreeC[F[_], +R] {

  // flatMap :: { this : FreeC f r } -> (r -> FreeC f r2) -> FreeC f r2
  // This is the monadic bind
  def flatMap[R2](f: R => FreeC[F, R2]): FreeC[F, R2] =
    // Construct new FreeC value of Bind variant
    // Note how this is stack safe because we return a value immediately, nothing is computed when we call flatMap
    Bind[F, R, R2](
      this,
      // Result r -> FreeC f r2
      e =>
        e match {
          // Unpack the result of the previous computation and either process it with the supplied function
          // or propagate the error, essentially just casting to the proper ADT
          case Result.Pure(r) =>
            try f(r)
            catch { case NonFatal(e) => FreeC.Result.Fail(e) }
          case Result.Interrupted(scope, err) => FreeC.Result.Interrupted(scope, err)
          case Result.Fail(e)                 => FreeC.Result.Fail(e)
      }
    )

  // transformWith :: { this : FreeC f r } -> (Result r -> FreeC f r2) -> FreeC f r2
  // Same as flatMap but takes a function from `Result r` directly to construct a `Bind` variant.
  def transformWith[R2](f: Result[R] => FreeC[F, R2]): FreeC[F, R2] =
    Bind[F, R, R2](this,
                   r =>
                     try f(r)
                     catch { case NonFatal(e) => FreeC.Result.Fail(e) })

  // map :: { this : FreeC f r } -> (r -> r2) -> FreeC f r2
  // Transforms the content of this FreeC
  def map[R2](f: R => R2): FreeC[F, R2] =
    // `map` is implemented with `Bind`, essentially `flatMap`
    // To do this we need to turn `r -> r2` into `Result r -> FreeC f r2`
    // We get `map :: Result r -> (r -> r2) -> Result r2` from the `Result` Monad instance
    // Then with help of some underscore magic we partially apply it to the second argument
    // and cast the result to `FreeC f r2` getting a new function of the exact type we need
    Bind[F, R, R2](this, 
                   // Can be written as `r => Result.monadInstance.map(r)(f).asFreeC[F]`
                   Result.monadInstance.map(_)(f).asFreeC[F])
  
  // handleErrorWith :: { this: FreeC f r } -> (Throwable -> FreeC f r2) -> FreeC f r2
  // Recover from an error state or do nothing.
  // Notice that r2 must be a supertype of r.
  // E.g. `r = String /\ r2 = Object` but `~(r = String /\ r2 = Int)`
  def handleErrorWith[R2 >: R](h: Throwable => FreeC[F, R2]): FreeC[F, R2] =
    Bind[F, R2, R2](this,
                    e =>
                      e match {
                        case Result.Fail(e) =>
                          try h(e)
                          catch { case NonFatal(e) => FreeC.Result.Fail(e) }
                        // Here we use the fact that r2 is a supertype of r, we can always upcast safely.
                        case other => other.covary[R2].asFreeC[F]
                    })

  // asHandler :: { this : FreeC f r } -> Throwable -> FreeC f r
  // TODO Looks like it injects a failure in the middle of a FreeC computation
  def asHandler(e: Throwable): FreeC[F, R] = 
  // unroll `FreeC f r` into `ViewL f r`
  ViewL(this) match {
    // Normal result translates into the provided faliure
    case Result.Pure(_)  => Result.Fail(e)
    // Failure translates into a composite failure containing the provided one
    case Result.Fail(e2) => Result.Fail(CompositeFailure(e2, e))
    // Interruption translates into interruption with the provided failure contained in the error part
    case Result.Interrupted(ctx, err) =>
      Result.Interrupted(ctx, err.map(t => CompositeFailure(e, t)).orElse(Some(e)))
    // A View ignores the current value and passes the provided failure to the next step instead
    case ViewL.View(_, k) => k(Result.Fail(e))
  }

  // viewL :: { this : FreeC f r } -> ViewL f r2
  // Unroll this `FreeC` into a `ViewL` potentially upcasting the result type
  def viewL[R2 >: R]: ViewL[F, R2] = ViewL(this)

  // translate :: { this : FreeC f r } -> (F ~> G) -> FreeC g r
  // Change the underlying functor from F to G provided a transformation `F ~> G`
  // Note that this implementation is overridden in all cases except `Bind`
  def translate[G[_]](f: F ~> G): FreeC[G, R] = FreeC.suspend {
    // This block is provided as a by-name argument to the `suspend` above
    // so again nothing is run at the point of construction,
    // the resulting `FreeC g r` will be a `Bind` with this block as the result value.
    
    // We first unroll the current `FreeC f r` into a `ViewL f r2` for any `r2 >: r`, it will unify to the original type `r`
    viewL match {
      // `fx :: f Any` -- current step.
      // `k :: Result Any -> FreeC f r` -- next step
      case ViewL.View(fx, k) =>
        // `Eval fx :: FreeC f Any`
        // Then we translate it to `G` recursively, if `fx` is not `Bind` it will just return the translated value and stop recursion.
        // In a sense we step from the end of our bind-chain to its beginning? TODO
        // So the first argument has type `FreeC g Any`
        // `e :: Result Any` |- `k e :: FreeC f r`, then we `translate` it into `FreeC g r`
        // So the second argument to bind has type `Result Any -> FreeC g r`
        // Hence `Bind(...)` has type `FreeC G r`
        // So we replaced all the "sources" of values to be under new functor and all the "continuations" to produce their results
        // into the new functor, thus translating this `FreeC` to use the new functor.
        Bind(Eval(fx).translate(f), (e: Result[Any]) => k(e).translate(f))
      // Any `Result` case class can be just upcast to `Free g` because they don't depend on the functor
      case r @ Result.Pure(_)           => r.asFreeC[G]
      case r @ Result.Fail(_)           => r.asFreeC[G]
      case r @ Result.Interrupted(_, _) => r.asFreeC[G]
    }
  }
}

private[fs2] object FreeC {

  def pure[F[_], A](a: A): FreeC[F, A] = Result.Pure(a)

  def eval[F[_], A](f: F[A]): FreeC[F, A] = Eval(f)

  def raiseError[F[_], A](rsn: Throwable): FreeC[F, A] = Result.Fail(rsn)

  def interrupted[F[_], X, A](interruptContext: X, failure: Option[Throwable]): FreeC[F, A] =
    Result.Interrupted(interruptContext, failure)

  sealed trait Result[+R] { self =>

    def asFreeC[F[_]]: FreeC[F, R] = self.asInstanceOf[FreeC[F, R]]

    def asExitCase: ExitCase[Throwable] = self match {
      case Result.Pure(_)           => ExitCase.Completed
      case Result.Fail(err)         => ExitCase.Error(err)
      case Result.Interrupted(_, _) => ExitCase.Canceled
    }

    def covary[R2 >: R]: Result[R2] = self

    def recoverWith[R2 >: R](f: Throwable => Result[R2]): Result[R2] = self match {
      case Result.Fail(err) =>
        try { f(err) } catch { case NonFatal(err2) => Result.Fail(CompositeFailure(err, err2)) }
      case _ => covary[R2]
    }

  }

  object Result {

    val unit: Result[Unit] = pure(())

    def pure[A](a: A): Result[A] = Result.Pure(a)

    def raiseError[A](rsn: Throwable): Result[A] = Result.Fail(rsn)

    def interrupted[A](scopeId: Token, failure: Option[Throwable]): Result[A] =
      Result.Interrupted(scopeId, failure)

    def fromEither[F[_], R](either: Either[Throwable, R]): Result[R] =
      either.fold(Result.Fail(_), Result.Pure(_))

    def unapply[F[_], R](freeC: FreeC[F, R]): Option[Result[R]] = freeC match {
      case r @ Result.Pure(_)           => Some(r: Result[R])
      case r @ Result.Fail(_)           => Some(r: Result[R])
      case r @ Result.Interrupted(_, _) => Some(r: Result[R])
      case _                            => None
    }

    final case class Pure[F[_], R](r: R) extends FreeC[F, R] with Result[R] with ViewL[F, R] {
      override def translate[G[_]](f: F ~> G): FreeC[G, R] =
        this.asInstanceOf[FreeC[G, R]]
      override def toString: String = s"FreeC.Pure($r)"
    }

    final case class Fail[F[_], R](error: Throwable)
        extends FreeC[F, R]
        with Result[R]
        with ViewL[F, R] {
      override def translate[G[_]](f: F ~> G): FreeC[G, R] =
        this.asInstanceOf[FreeC[G, R]]
      override def toString: String = s"FreeC.Fail($error)"
    }

    /**
      * Signals that FreeC evaluation was interrupted.
      *
      * @param context Any user specific context that needs to be captured during interruption
      *                for eventual resume of the operation.
      *
      * @param deferredError Any errors, accumulated during resume of the interruption.
      *                      Instead throwing errors immediately during interruption,
      *                      signalling of the errors may be deferred until the Interruption resumes.
      */
    final case class Interrupted[F[_], X, R](context: X, deferredError: Option[Throwable])
        extends FreeC[F, R]
        with Result[R]
        with ViewL[F, R] {
      override def translate[G[_]](f: F ~> G): FreeC[G, R] =
        this.asInstanceOf[FreeC[G, R]]
      override def toString: String =
        s"FreeC.Interrupted($context, ${deferredError.map(_.getMessage)})"
    }

    val monadInstance: Monad[Result] = new Monad[Result] {
      def flatMap[A, B](fa: Result[A])(f: A => Result[B]): Result[B] = fa match {
        case Result.Pure(r) =>
          try { f(r) } catch { case NonFatal(err) => Result.Fail(err) }
        case failure @ Result.Fail(_)               => failure.asInstanceOf[Result[B]]
        case interrupted @ Result.Interrupted(_, _) => interrupted.asInstanceOf[Result[B]]
      }

      def tailRecM[A, B](a: A)(f: A => Result[Either[A, B]]): Result[B] = {
        @tailrec
        def go(a: A): Result[B] =
          f(a) match {
            case Result.Pure(Left(a))                   => go(a)
            case Result.Pure(Right(b))                  => Result.Pure(b)
            case failure @ Result.Fail(_)               => failure.asInstanceOf[Result[B]]
            case interrupted @ Result.Interrupted(_, _) => interrupted.asInstanceOf[Result[B]]
          }

        try { go(a) } catch { case t: Throwable => Result.Fail(t) }
      }

      def pure[A](x: A): Result[A] = Result.Pure(x)
    }

  }

  final case class Eval[F[_], R](fr: F[R]) extends FreeC[F, R] {
    override def translate[G[_]](f: F ~> G): FreeC[G, R] =
      try Eval(f(fr))
      catch { case NonFatal(t) => Result.Fail[G, R](t) }
    override def toString: String = s"FreeC.Eval($fr)"
  }
  final case class Bind[F[_], X, R](fx: FreeC[F, X], f: Result[X] => FreeC[F, R])
      extends FreeC[F, R] {
    override def toString: String = s"FreeC.Bind($fx, $f)"
  }

  // pureContinuation :: forall f. Result r -> FreeC f r
  // Turns `Result` into `FreeC f`
  def pureContinuation[F[_], R]: Result[R] => FreeC[F, R] =
    _.asFreeC[F]

  // suspend :: ( () -> FreeC f r ) -> FreeC f r
  // Takes a by-name FreeC value and returns a FreeC value that will result in the provided value when "run"
  def suspend[F[_], R](fr: => FreeC[F, R]): FreeC[F, R] =
    // Just flatmap over a unit Result, this will construct a `Bind` varian of `FreeC`
    // with provided computation as the result.
    Result.Pure[F, Unit](()).flatMap(_ => fr)

  /**
    * Unrolled view of a `FreeC` structure. may be `Result` or `EvalBind`
    */
  sealed trait ViewL[F[_], +R]

  object ViewL {

    /** unrolled view of FreeC `bind` structure **/
    final case class View[F[_], X, R](step: F[X], next: Result[X] => FreeC[F, R])
        extends ViewL[F, R]

    // apply :: FreeC f r -> ViewL f r
    // Unrolls the provided `FreeC f r` into a `ViewL f r`
    private[fs2] def apply[F[_], R](free: FreeC[F, R]): ViewL[F, R] = mk(free)

    @tailrec
    private def mk[F[_], R](free: FreeC[F, R]): ViewL[F, R] =
      free match {
        case Eval(fx) => View(fx, pureContinuation[F, R])
        case b: FreeC.Bind[F, y, R] =>
          b.fx match {
            case Result(r)  => mk(b.f(r))
            case Eval(fr)   => ViewL.View(fr, b.f)
            case Bind(w, g) => mk(Bind(w, (e: Result[Any]) => Bind(g(e), b.f)))
          }
        case r @ Result.Pure(_)           => r
        case r @ Result.Fail(_)           => r
        case r @ Result.Interrupted(_, _) => r
      }

  }

  implicit final class InvariantOps[F[_], R](private val self: FreeC[F, R]) extends AnyVal {
    // None indicates the FreeC was interrupted
    def run(implicit F: MonadError[F, Throwable]): F[Option[R]] =
      self.viewL match {
        case Result.Pure(r)             => F.pure(Some(r))
        case Result.Fail(e)             => F.raiseError(e)
        case Result.Interrupted(_, err) => err.fold[F[Option[R]]](F.pure(None)) { F.raiseError }
        case ViewL.View(step, next) =>
          F.flatMap(F.attempt(step)) { r =>
            next(Result.fromEither(r)).run
          }
      }
  }

  implicit def syncInstance[F[_]]: Sync[FreeC[F, ?]] = new Sync[FreeC[F, ?]] {
    def pure[A](a: A): FreeC[F, A] = FreeC.Result.Pure(a)
    def handleErrorWith[A](fa: FreeC[F, A])(f: Throwable => FreeC[F, A]): FreeC[F, A] =
      fa.handleErrorWith(f)
    def raiseError[A](t: Throwable): FreeC[F, A] = FreeC.Result.Fail(t)
    def flatMap[A, B](fa: FreeC[F, A])(f: A => FreeC[F, B]): FreeC[F, B] =
      fa.flatMap(f)
    def tailRecM[A, B](a: A)(f: A => FreeC[F, Either[A, B]]): FreeC[F, B] =
      f(a).flatMap {
        case Left(a)  => tailRecM(a)(f)
        case Right(b) => pure(b)
      }
    def suspend[A](thunk: => FreeC[F, A]): FreeC[F, A] = FreeC.suspend(thunk)
    def bracketCase[A, B](acquire: FreeC[F, A])(use: A => FreeC[F, B])(
        release: (A, ExitCase[Throwable]) => FreeC[F, Unit]): FreeC[F, B] =
      acquire.flatMap { a =>
        val used =
          try use(a)
          catch { case NonFatal(t) => FreeC.Result.Fail[F, B](t) }
        used.transformWith { result =>
          release(a, result.asExitCase).transformWith {
            case Result.Fail(t2) =>
              result
                .recoverWith { t =>
                  Result.Fail(CompositeFailure(t, t2))
                }
                .asFreeC[F]
            case _ => result.asFreeC[F]
          }
        }
      }
  }
}
