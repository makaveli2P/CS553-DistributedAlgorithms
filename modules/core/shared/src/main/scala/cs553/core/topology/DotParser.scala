package cs553.core.topology

import cats.data.NonEmptyList
import io.circe.Codec

import scala.annotation.tailrec

/** The kind of a DOT graph: `graph` with `--` edges, or `digraph` with `->` edges. */
enum GraphKind {
  case Undirected, Directed
}

/** One node of a parsed DOT graph, with its merged attributes. */
final case class DotNode(id: String, attrs: Map[String, String])

/** One edge of a parsed DOT graph. An edge chain `a -> b -> c` gives two edges. */
final case class DotEdge(from: String, to: String, attrs: Map[String, String])

/** The result of `DotParser.parse`. The node list keeps the order of the first occurrence. */
final case class DotGraph(
    kind: GraphKind,
    name: Option[String],
    attrs: Map[String, String],
    nodes: List[DotNode],
    edges: List[DotEdge]
)

/** A parse error. The line and the column start at 1. */
final case class DotError(line: Int, column: Int, message: String) derives Codec.AsObject {
  def render: String = s"line $line, column $column: $message"
}

/** A parser for the DOT subset that the topology fixtures use.
  *
  * The subset is: `strict`, `graph` and `digraph`, an optional graph name, node statements, edge
  * statements with `--` or `->` (also chains), attribute statements for `graph`, `node` and `edge`,
  * `id = id` graph attributes, attribute lists, plain, numeral, quoted and HTML ids, and the
  * comments `//`, `#` and `/* */`. The parser rejects subgraphs and ports with a clear error.
  *
  * The input is a string. The parser does no IO.
  */
object DotParser {

  def parse(source: String): Either[NonEmptyList[DotError], DotGraph] = {
    Lexer.tokens(source).flatMap(Parser.graph).left.map(NonEmptyList.one)
  }

  // ---------------------------------------------------------------------------------------------
  // Tokens

  private enum IdForm {
    case Plain, Numeral, Quoted, Html
  }

  private enum Tok {
    case Id(text: String, form: IdForm)
    case LBrace, RBrace, LBracket, RBracket, Equals, Semicolon, Comma, Arrow, Line, End

    def describe: String = this match {
      case Id(text, IdForm.Html) => s"the HTML id <$text>"
      case Id(text, _)           => s"'$text'"
      case LBrace                => "'{'"
      case RBrace                => "'}'"
      case LBracket              => "'['"
      case RBracket              => "']'"
      case Equals                => "'='"
      case Semicolon             => "';'"
      case Comma                 => "','"
      case Arrow                 => "'->'"
      case Line                  => "'--'"
      case End                   => "the end of the input"
    }
  }

  private final case class Token(tok: Tok, line: Int, column: Int) {
    def error(message: String): DotError = DotError(line, column, message)
  }

  private final case class Pos(index: Int, line: Int, column: Int) {
    def error(message: String): DotError = DotError(line, column, message)
  }

  // ---------------------------------------------------------------------------------------------
  // Lexer

  private object Lexer {

    def tokens(src: String): Either[DotError, Vector[Token]] = loop(src, Pos(0, 1, 1), Vector.empty)

    @tailrec
    private def loop(src: String, pos: Pos, acc: Vector[Token]): Either[DotError, Vector[Token]] = {
      if pos.index >= src.length then { Right(acc :+ Token(Tok.End, pos.line, pos.column)) }
      else {
        scan(src, pos) match {
          case Left(error)                => Left(error)
          case Right((None, next))        => loop(src, next, acc)
          case Right((Some(token), next)) => loop(src, next, acc :+ token)
        }
      }
    }

    private def advance(src: String, pos: Pos, to: Int): Pos = {
      src.slice(pos.index, to).foldLeft(pos) { (p, c) =>
        if c == '\n' then { Pos(p.index + 1, p.line + 1, 1) }
        else { Pos(p.index + 1, p.line, p.column + 1) }
      }
    }

    private def at(src: String, index: Int): Option[Char] = {
      if index >= 0 && index < src.length then { Some(src.charAt(index)) }
      else { None }
    }

    private def token(
        src: String,
        pos: Pos,
        tok: Tok,
        end: Int
    ): Right[DotError, (Option[Token], Pos)] = {
      Right((Some(Token(tok, pos.line, pos.column)), advance(src, pos, end)))
    }

    private def scan(src: String, pos: Pos): Either[DotError, (Option[Token], Pos)] = {
      val i = pos.index
      val c = src.charAt(i)
      val next = at(src, i + 1)
      if c.isWhitespace then { Right((None, advance(src, pos, i + 1))) }
      else if c == '#' || (c == '/' && next.contains('/')) then {
        val end = src.indexOf('\n', i)
        Right(
          (
            None,
            advance(
              src,
              pos,
              if end < 0 then { src.length }
              else { end }
            )
          )
        )
      } else if c == '/' && next.contains('*') then {
        val end = src.indexOf("*/", i + 2)
        if end < 0 then { Left(pos.error("the comment has no closing '*/'")) }
        else { Right((None, advance(src, pos, end + 2))) }
      } else if c == '"' then { quoted(src, pos) }
      else if c == '<' then { html(src, pos) }
      else if c == '-' && next.contains('>') then { token(src, pos, Tok.Arrow, i + 2) }
      else if c == '-' && next.contains('-') then { token(src, pos, Tok.Line, i + 2) }
      else if c == '{' then { token(src, pos, Tok.LBrace, i + 1) }
      else if c == '}' then { token(src, pos, Tok.RBrace, i + 1) }
      else if c == '[' then { token(src, pos, Tok.LBracket, i + 1) }
      else if c == ']' then { token(src, pos, Tok.RBracket, i + 1) }
      else if c == '=' then { token(src, pos, Tok.Equals, i + 1) }
      else if c == ';' then { token(src, pos, Tok.Semicolon, i + 1) }
      else if c == ',' then { token(src, pos, Tok.Comma, i + 1) }
      else if c == ':' then { Left(pos.error("ports (':') are not supported")) }
      else if isIdStart(c) then {
        val end = scanWhile(src, i + 1, isIdPart)
        token(src, pos, Tok.Id(src.substring(i, end), IdForm.Plain), end)
      } else if c.isDigit || c == '.' || c == '-' then { numeral(src, pos) }
      else { Left(pos.error(s"the character '$c' is not expected")) }
    }

    private def isIdStart(c: Char): Boolean = c.isLetter || c == '_' || c > '\u007f'

    private def isIdPart(c: Char): Boolean = isIdStart(c) || c.isDigit

    @tailrec
    private def scanWhile(src: String, i: Int, ok: Char => Boolean): Int = {
      if i < src.length && ok(src.charAt(i)) then { scanWhile(src, i + 1, ok) }
      else { i }
    }

    private def numeral(src: String, pos: Pos): Either[DotError, (Option[Token], Pos)] = {
      val start = pos.index
      val afterSign = if src.charAt(start) == '-' then { start + 1 }
      else { start }
      val afterInt = scanWhile(src, afterSign, _.isDigit)
      val end = if at(src, afterInt).contains('.') then { scanWhile(src, afterInt + 1, _.isDigit) }
      else { afterInt }
      val text = src.substring(start, end)
      if !text.exists(_.isDigit) then { Left(pos.error(s"'$text' is not a valid numeral")) }
      else if at(src, end).exists(isIdStart) then {
        Left(pos.error(s"the numeral '$text' runs into the letter '${src.charAt(end)}'"))
      } else { token(src, pos, Tok.Id(text, IdForm.Numeral), end) }
    }

    private def quoted(src: String, pos: Pos): Either[DotError, (Option[Token], Pos)] = {
      @tailrec
      def close(j: Int): Option[Int] = {
        if j >= src.length then { None }
        else if src.charAt(j) == '\\' then { close(j + 2) }
        else if src.charAt(j) == '"' then { Some(j) }
        else { close(j + 1) }
      }
      close(pos.index + 1) match {
        case None      => Left(pos.error("the quoted string has no closing '\"'"))
        case Some(end) =>
          val text = src.substring(pos.index + 1, end).replace("\\\"", "\"").replace("\\\n", "")
          token(src, pos, Tok.Id(text, IdForm.Quoted), end + 1)
      }
    }

    private def html(src: String, pos: Pos): Either[DotError, (Option[Token], Pos)] = {
      @tailrec
      def close(j: Int, depth: Int): Option[Int] = {
        if j >= src.length then { None }
        else if src.charAt(j) == '<' then { close(j + 1, depth + 1) }
        else if src.charAt(j) == '>' && depth == 1 then { Some(j) }
        else if src.charAt(j) == '>' then { close(j + 1, depth - 1) }
        else { close(j + 1, depth) }
      }
      close(pos.index + 1, 1) match {
        case None      => Left(pos.error("the HTML id has no closing '>'"))
        case Some(end) =>
          token(src, pos, Tok.Id(src.substring(pos.index + 1, end), IdForm.Html), end + 1)
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Parser

  private final case class Acc(
      attrs: Map[String, String],
      nodeDefaults: Map[String, String],
      edgeDefaults: Map[String, String],
      order: Vector[String],
      nodes: Map[String, Map[String, String]],
      edges: Vector[DotEdge]
  ) {
    def touch(id: String): Acc = {
      if nodes.contains(id) then { this }
      else { copy(order = order :+ id, nodes = nodes.updated(id, nodeDefaults)) }
    }

    def node(id: String, extra: Map[String, String]): Acc = {
      val added = touch(id)
      added.copy(nodes = added.nodes.updated(id, added.nodes.getOrElse(id, Map.empty) ++ extra))
    }

    def edge(from: String, to: String, extra: Map[String, String]): Acc = {
      val added = touch(from).touch(to)
      added.copy(edges = added.edges :+ DotEdge(from, to, edgeDefaults ++ extra))
    }
  }

  private object Acc {
    val empty: Acc = Acc(Map.empty, Map.empty, Map.empty, Vector.empty, Map.empty, Vector.empty)
  }

  private object Parser {

    private type Result[A] = Either[DotError, (A, Int)]

    def graph(tokens: Vector[Token]): Either[DotError, DotGraph] = {
      val afterStrict = if isKeyword(tokens, 0, "strict") then { 1 }
      else { 0 }
      for {
        kind <- {
          if isKeyword(tokens, afterStrict, "graph") then { Right(GraphKind.Undirected) }
          else if isKeyword(tokens, afterStrict, "digraph") then { Right(GraphKind.Directed) }
          else { Left(unexpected(tokens, afterStrict, "'graph' or 'digraph'")) }
        }
        named <- {
          val i = afterStrict + 1
          tok(tokens, i) match {
            case Tok.Id(text, form) if !isAnyKeyword(text, form) => Right((Some(text), i + 1))
            case _                                               => Right((None, i))
          }
        }
        (name, afterName) = named
        afterBrace <- expect(tokens, afterName, Tok.LBrace)
        body <- statements(tokens, kind, afterBrace, Acc.empty)
        (acc, afterBody) = body
        afterClose <- expect(tokens, afterBody, Tok.RBrace)
        _ <- expect(tokens, afterClose, Tok.End)
      } yield {
        DotGraph(
          kind,
          name,
          acc.attrs,
          acc.order.toList.map(id => DotNode(id, acc.nodes.getOrElse(id, Map.empty))),
          acc.edges.toList
        )
      }
    }

    private def at(tokens: Vector[Token], i: Int): Token = {
      tokens.lift(i).getOrElse(tokens.lastOption.getOrElse(Token(Tok.End, 1, 1)))
    }

    private def tok(tokens: Vector[Token], i: Int): Tok = at(tokens, i).tok

    private def unexpected(tokens: Vector[Token], i: Int, wanted: String): DotError = {
      at(tokens, i).error(s"expected $wanted but found ${tok(tokens, i).describe}")
    }

    private def expect(tokens: Vector[Token], i: Int, wanted: Tok): Either[DotError, Int] = {
      if tok(tokens, i) == wanted then { Right(i + 1) }
      else { Left(unexpected(tokens, i, wanted.describe)) }
    }

    private val keywords: Set[String] =
      Set("strict", "graph", "digraph", "node", "edge", "subgraph")

    private def isAnyKeyword(text: String, form: IdForm): Boolean = {
      form == IdForm.Plain && keywords.contains(text.toLowerCase)
    }

    private def isKeyword(tokens: Vector[Token], i: Int, word: String): Boolean = {
      tok(tokens, i) match {
        case Tok.Id(text, IdForm.Plain) => text.equalsIgnoreCase(word)
        case _                          => false
      }
    }

    private def id(tokens: Vector[Token], i: Int, wanted: String): Result[String] = {
      tok(tokens, i) match {
        case Tok.Id(text, form) if !isAnyKeyword(text, form) => Right((text, i + 1))
        case _                                               => Left(unexpected(tokens, i, wanted))
      }
    }

    @tailrec
    private def statements(
        tokens: Vector[Token],
        kind: GraphKind,
        i: Int,
        acc: Acc
    ): Result[Acc] = {
      tok(tokens, i) match {
        case Tok.RBrace | Tok.End => Right((acc, i))
        case Tok.Semicolon        => statements(tokens, kind, i + 1, acc)
        case _                    =>
          statement(tokens, kind, i, acc) match {
            case Left(error)          => Left(error)
            case Right((next, after)) => statements(tokens, kind, after, next)
          }
      }
    }

    private def statement(tokens: Vector[Token], kind: GraphKind, i: Int, acc: Acc): Result[Acc] = {
      if isKeyword(tokens, i, "subgraph") || tok(tokens, i) == Tok.LBrace then {
        Left(at(tokens, i).error("subgraphs are not supported"))
      } else if isKeyword(tokens, i, "graph") then {
        attrLists(tokens, i + 1).map { case (a, after) =>
          (acc.copy(attrs = acc.attrs ++ a), after)
        }
      } else if isKeyword(tokens, i, "node") then {
        attrLists(tokens, i + 1).map { case (a, after) =>
          (acc.copy(nodeDefaults = acc.nodeDefaults ++ a), after)
        }
      } else if isKeyword(tokens, i, "edge") then {
        attrLists(tokens, i + 1).map { case (a, after) =>
          (acc.copy(edgeDefaults = acc.edgeDefaults ++ a), after)
        }
      } else {
        id(tokens, i, "a statement").flatMap { case (first, afterFirst) =>
          tok(tokens, afterFirst) match {
            case Tok.Equals =>
              id(tokens, afterFirst + 1, "an attribute value").map { case (value, after) =>
                (acc.copy(attrs = acc.attrs.updated(first, value)), after)
              }
            case Tok.Arrow | Tok.Line => edgeChain(tokens, kind, first, afterFirst, acc)
            case _                    =>
              optionalAttrLists(tokens, afterFirst).map { case (a, after) =>
                (acc.node(first, a), after)
              }
          }
        }
      }
    }

    private def edgeChain(
        tokens: Vector[Token],
        kind: GraphKind,
        first: String,
        i: Int,
        acc: Acc
    ): Result[Acc] = {
      val wantedOp = kind match {
        case GraphKind.Undirected => Tok.Line
        case GraphKind.Directed   => Tok.Arrow
      }
      @tailrec
      def targets(j: Int, ids: Vector[String]): Result[Vector[String]] = {
        tok(tokens, j) match {
          case op @ (Tok.Arrow | Tok.Line) if op != wantedOp =>
            Left(
              at(tokens, j).error(
                s"the edge operator ${op.describe} does not match the graph kind ($kind)"
              )
            )
          case Tok.Arrow | Tok.Line =>
            id(tokens, j + 1, "a node id after the edge operator") match {
              case Left(error)        => Left(error)
              case Right((to, after)) => targets(after, ids :+ to)
            }
          case _ => Right((ids, j))
        }
      }
      for {
        chain <- targets(i, Vector(first))
        (ids, afterIds) = chain
        lists <- optionalAttrLists(tokens, afterIds)
        (a, after) = lists
      } yield {
        val updated = ids.zip(ids.drop(1)).foldLeft(acc) { case (next, (from, to)) =>
          next.edge(from, to, a)
        }
        (updated, after)
      }
    }

    private def optionalAttrLists(tokens: Vector[Token], i: Int): Result[Map[String, String]] = {
      if tok(tokens, i) == Tok.LBracket then { attrLists(tokens, i) }
      else { Right((Map.empty, i)) }
    }

    /** One or more `[ a = b, c = d ]` lists. */
    private def attrLists(tokens: Vector[Token], i: Int): Result[Map[String, String]] = {
      @tailrec
      def lists(j: Int, acc: Map[String, String]): Result[Map[String, String]] = {
        if tok(tokens, j) != Tok.LBracket then { Right((acc, j)) }
        else {
          pairs(j + 1, acc) match {
            case Left(error)          => Left(error)
            case Right((next, after)) => lists(after, next)
          }
        }
      }
      @tailrec
      def pairs(j: Int, acc: Map[String, String]): Result[Map[String, String]] = {
        tok(tokens, j) match {
          case Tok.RBracket              => Right((acc, j + 1))
          case Tok.Comma | Tok.Semicolon => pairs(j + 1, acc)
          case _                         =>
            val pair = for {
              k <- id(tokens, j, "an attribute name or ']'")
              (key, afterKey) = k
              afterEq <- expect(tokens, afterKey, Tok.Equals)
              v <- id(tokens, afterEq, "an attribute value")
            } yield (key, v)
            pair match {
              case Left(error)                     => Left(error)
              case Right((key, (value, afterVal))) => pairs(afterVal, acc.updated(key, value))
            }
        }
      }
      if tok(tokens, i) != Tok.LBracket then { Left(unexpected(tokens, i, "'['")) }
      else { lists(i, Map.empty) }
    }
  }
}
