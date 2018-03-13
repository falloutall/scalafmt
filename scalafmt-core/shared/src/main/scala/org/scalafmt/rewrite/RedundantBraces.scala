package org.scalafmt.rewrite

import scala.meta.Tree
import scala.meta._
import scala.meta.tokens.Token.LF
import scala.meta.tokens.Token.LeftBrace
import scala.meta.tokens.Token.RightBrace
import org.scalafmt.util.TreeOps._

/**
  * Removes/adds curly braces where desired.
  */
case object RedundantBraces extends Rewrite {

  private type PatchBuilder = scala.collection.mutable.Builder[Patch, Seq[Patch]]

  private def isDefnCandidate(d: Defn.Def)(implicit ctx: RewriteCtx): Boolean = {
    import ctx.style.rewrite.{redundantBraces => settings}
    def isBlock = d.body match {
      case Term.Block(Seq(stat)) =>
        (stat match {
          case _: Term.Block | _: Term.Function | _: Defn => false
          case _ => true
        }) &&
          d.body.tokens.head.is[LeftBrace] &&
          d.body.tokens.last.is[RightBrace]
      case _ => false
    }

    def disqualifiedByUnit =
      !settings.includeUnitMethods && d.decltpe.exists(_.syntax == "Unit")

    def bodyIsNotTooBig: Boolean =
      d.body match {
        case t: Term.Block =>
          val stat = t.stats.head
          val diff =
            stat.tokens.last.pos.end.line -
              stat.tokens.head.pos.start.line
          diff < settings.maxLines
        case _ => false
      }

    !isProcedureSyntax(d) &&
    isBlock &&
    !disqualifiedByUnit &&
    bodyIsNotTooBig
  }

  private def isIdentifierAtStart(value: String) =
    value.nonEmpty && (Character.isLetterOrDigit(value.head) || value.head == '_')

  override def rewrite(code: Tree, ctx: RewriteCtx): Seq[Patch] = {
    implicit def _ctx = ctx
    import ctx.tokenTraverser._
    import ctx.style.rewrite.{redundantBraces => settings}
    implicit val builder = Seq.newBuilder[Patch]

    code.traverse {
      case t: Term.Interpolate if settings.stringInterpolation =>
        t.parts.tail.zip(t.args).foreach {
          case (Lit(value: String), arg @ Term.Name(name))
              if !isIdentifierAtStart(value) =>
            val openBrace = prevToken(arg.tokens.head)
            val closeBrace = nextToken(arg.tokens.head)
            (openBrace, closeBrace) match {
              case (LeftBrace(), RightBrace()) =>
                builder += TokenPatch.Remove(openBrace)
                builder += TokenPatch.Remove(closeBrace)
              case _ =>
            }
          case _ =>
        }
      case d: Defn.Def if isDefnCandidate(d) =>
        val open = d.body.tokens.head
        val close = d.body.tokens.last
        val bodyStatement = d.body match {
          case t: Term.Block => t.stats.head
          case _ => d.body
        }
        removeTrailingLF(bodyStatement.pos, close)
        builder += TokenPatch.Remove(open)
        builder += TokenPatch.Remove(close)
    }
    builder.result()
  }

  private def removeTrailingLF(bodyEnd: Position, close: Token)
                              (implicit builder: PatchBuilder, ctx: RewriteCtx): Unit = {
    import ctx.tokenTraverser._
    val next = nextToken(close)
    if (next.is[LF] && close.pos.start.line != bodyEnd.end.line)
      builder += TokenPatch.Remove(next)
  }
}
