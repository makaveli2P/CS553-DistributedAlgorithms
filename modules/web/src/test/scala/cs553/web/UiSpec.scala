package cs553.web

import cs553.core.ids.AlgorithmId
import cs553.core.registry.Registry
import cs553.core.rng.Seed
import cs553.core.trace.TraceEvent
import cs553.core.world.Simulator
import org.scalatest.funsuite.AnyFunSuite

import scala.annotation.tailrec

final class UiSpec extends AnyFunSuite {

  private def open(id: String): UiState =
    Ui.update(Ui.initial, UiEvent.Navigate(Route.Open(AlgorithmId(id))))

  private def session(state: UiState): Option[Session[?, ?, ?, ?]] = state.page match {
    case Page.Sandbox(s, _) => Some(s)
    case Page.Home(_)       => None
  }

  private def view(state: UiState): Option[View] = state.page match {
    case Page.Sandbox(_, v) => Some(v)
    case Page.Home(_)       => None
  }

  private def apply(state: UiState, events: UiEvent*): UiState = events.foldLeft(state)(Ui.update)

  @tailrec
  private def runToEnd(state: UiState, limit: Int): UiState = {
    val done = session(state).forall(s => s.finished && s.live)
    if done || limit == 0 then { state }
    else { runToEnd(Ui.update(state, UiEvent.Forward), limit - 1) }
  }

  test("a route renders to a fragment and parses back") {
    val routes = List(Route.Home, Route.Open(AlgorithmId("echo")), Route.Replay("1.abc_-", Some(4)))
    routes.foreach(r => assert(Route.parse(Route.render(r)) == r))
    assert(Route.parse("") == Route.Home)
    assert(Route.parse("#/run/1.abc/x") == Route.Replay("1.abc", None))
  }

  test("opening Echo shows step 0 with the initiator action as the primary action") {
    val state = open("echo")
    val screen = Ui.screen(state)
    assert(screen.map(_.frame.step) == Some(0))
    assert(screen.flatMap(_.primary).map(_.label) == Some("Wake node 1"))
    assert(screen.map(_.panel) == Some(None))
    assert(screen.exists(_.frame.nodes.exists(n => n.actions.map(_.label) == List("Wake"))))
  }

  test("an unknown algorithm or a bad link goes home with one notice") {
    assert(Ui.notice(open("nope")).exists(_.contains("nope")))
    val bad = Ui.update(Ui.initial, UiEvent.Navigate(Route.Replay("2.xyz", None)))
    assert(Ui.notice(bad).exists(_.startsWith("This link could not be read")))
  }

  test("Deliver next steps give the same trace as a seeded Simulator run") {
    Registry.all.foreach { any =>
      val alg = any.algorithm
      val end = runToEnd(open(any.id.value), 500)
      val expected = alg.defaultSpec.map(alg.simulate)
      val got = session(end).map(_.sim.run.trace.map(_.toString))
      assert(got.nonEmpty, any.name)
      assert(expected.flatMap(_.toOption).map(_.run.trace.map(_.toString)) == got, any.name)
      assert(Ui.screen(end).flatMap(_.primary).map(_.label) == Some("Run again"), any.name)
      assert(Ui.screen(end).flatMap(_.frame.quiescentAt).nonEmpty, any.name)
      assert(Ui.screen(end).flatMap(_.frame.decidedAt).nonEmpty, any.name)
    }
  }

  test("a scrub moves only the cursor, and Continue starts a branch with the first k choices") {
    val end = runToEnd(open("chang-roberts"), 500)
    val before = session(end)
    val back = apply(end, UiEvent.Seek(5))
    assert(session(back).map(_.cursor) == Some(5))
    assert(session(back).map(_.sim) == before.map(_.sim))
    assert(Ui.screen(back).flatMap(_.primary).map(_.label) == Some("Continue from step 5"))
    val branch = apply(back, UiEvent.ContinueHere)
    assert(session(branch).map(_.last) == Some(5))
    assert(session(branch).map(_.sim.choices) == before.map(_.sim.choices.take(5)))
    assert(Ui.screen(branch).flatMap(_.branchLine).exists(_.contains("first 5 choices")))
    assert(Ui.screen(branch).flatMap(_.primary).map(_.label).exists(_ != "Continue from step 5"))
  }

  test("the URL route of a state replays the same run at the same step") {
    val mid = apply(runToEnd(open("echo"), 500), UiEvent.Seek(7))
    val reopened = Ui.update(Ui.initial, UiEvent.Navigate(Ui.route(mid)))
    assert(session(reopened).map(_.cursor) == Some(7))
    assert(session(reopened).map(_.sim.run.trace) == session(mid).map(_.sim.run.trace))
    assert(Ui.route(reopened) == Ui.route(mid))
  }

  test("a FIFO channel blocks each envelope behind its head, and a click on it changes nothing") {
    val alg = Registry.changRoberts
    val queued = (1L to 20L).iterator
      .flatMap { seed =>
        alg.presets.flatMap { preset =>
          Session.start(alg, alg.spec(preset, Seed(seed))).toOption.toList.flatMap { s0 =>
            LazyList.iterate(s0)(_.deliverNext).take(80).find { s =>
              s.frame.exists(_.channels.exists(_.queue.size >= 2))
            }
          }
        }
      }
      .nextOption()
    assert(queued.nonEmpty)
    queued.foreach { s =>
      val channel = s.frame.toList.flatMap(_.channels).find(_.queue.size >= 2)
      channel.foreach { c =>
        assert(c.queue.headOption.exists(_.blocked.isEmpty))
        c.queue.drop(1).foreach(e => assert(e.blocked.exists(_.isInstanceOf[Blocked.Behind])))
        c.queue.lift(1).foreach { e =>
          val after = s.deliver(e.id)
          assert(after.sim == s.sim)
          assert(after.refused.nonEmpty)
        }
      }
    }
  }

  test("Play ticks forward and stops at the end of the run") {
    val playing = apply(open("echo"), UiEvent.TogglePlay)
    assert(Ui.tickInterval(playing).nonEmpty)
    val ticked = (1 to 200).foldLeft(playing)((s, _) => Ui.update(s, UiEvent.Tick))
    assert(session(ticked).exists(s => s.finished && s.live))
    assert(view(ticked).map(_.playing) == Some(false))
    assert(Ui.tickInterval(ticked) == None)
  }

  test("a settings change starts a new run at step 0") {
    val moved =
      apply(open("chang-roberts"), UiEvent.Forward, UiEvent.Forward, UiEvent.ChooseSeed(9))
    assert(session(moved).map(_.cursor) == Some(0))
    assert(session(moved).map(_.seed.value) == Some(9L))
    val ring8 = apply(moved, UiEvent.ChoosePreset("ring-8"))
    assert(session(ring8).flatMap(_.presetName) == Some("ring-8"))
    assert(Ui.screen(ring8).map(_.frame.nodes.size) == Some(8))
  }

  test("a node click opens its panel, a second click closes it, and the panel uses Inspect") {
    val woke = apply(open("chang-roberts"), UiEvent.Forward)
    val node = session(woke).flatMap(s =>
      s.sim.run.trace.collectFirst { case TraceEvent.Stepped(_, n, _, _, _, _) => n }
    )
    node.foreach { n =>
      val opened = apply(woke, UiEvent.SelectNode(n))
      Ui.screen(opened).flatMap(_.panel) match {
        case Some(PanelView.NodeInfo(id, _, fields, _, rule, _)) =>
          assert(id == n)
          assert(fields.exists(_._1 == "phase"))
          assert(rule.exists(_.startsWith("wake")))
        case other => fail(s"expected a node panel, got $other")
      }
      assert(Ui.screen(apply(opened, UiEvent.SelectNode(n))).flatMap(_.panel) == None)
    }
    assert(node.nonEmpty)
  }

  test("seeded stepping after a manual choice matches Simulator.seededChoice") {
    val alg = Registry.echo
    val s0 = alg.defaultSpec.flatMap(spec => Session.start(alg, spec).toOption)
    s0.foreach { s =>
      val woke = s.deliverNext
      val expected = woke.sim.last.flatMap { w =>
        Simulator.seededChoice(woke.seed, w.step, cs553.core.world.World.enabled(alg.protocol, w))
      }
      assert(woke.nextChoice == expected)
    }
    assert(s0.nonEmpty)
  }
}
