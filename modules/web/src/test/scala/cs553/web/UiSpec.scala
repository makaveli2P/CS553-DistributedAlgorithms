package cs553.web

import cs553.core.codec.SharedRun
import cs553.core.ids.AlgorithmId
import cs553.core.ids.NodeId
import cs553.core.registry.Registry
import cs553.core.rng.Seed
import cs553.core.trace.Choice
import cs553.core.trace.Policy
import cs553.core.trace.TraceEvent
import cs553.core.world.Simulator
import cs553.core.world.World
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

  private def channelWith2(f: Frame): Option[List[EnvelopeView]] = {
    f.envelopes.groupBy(e => (e.id.from, e.id.to)).values.map(_.sortBy(_.place)).find(_.size >= 2)
  }

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
    assert(Ui.notice(open("nope")).exists(_.text.contains("nope")))
    val bad = Ui.update(Ui.initial, UiEvent.Navigate(Route.Replay("2.xyz", None)))
    assert(Ui.notice(bad).map(_.text) == Some(Ui.damagedLink))
    // The detail stays for a developer, but the learner reads one plain line.
    assert(Ui.notice(bad).exists(_.detail.contains("version")))
    val garbage = Ui.update(Ui.initial, UiEvent.Navigate(Route.Replay("1.@@@@", Some(2))))
    assert(Ui.notice(garbage).map(_.text) == Some(Ui.damagedLink))
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
              s.frame(Layout.defaultSize).exists(channelWith2(_).nonEmpty)
            }
          }
        }
      }
      .nextOption()
    assert(queued.nonEmpty)
    queued.foreach { s =>
      val channel = s.frame(Layout.defaultSize).toList.flatMap(channelWith2)
      assert(channel.nonEmpty)
      channel.headOption.foreach { queue =>
        assert(queue.headOption.exists(_.blocked.isEmpty))
        queue.drop(1).foreach(e => assert(e.blocked.exists(_.isInstanceOf[Blocked.Behind])))
        queue.lift(1).foreach { e =>
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
        case Some(PanelView.NodeInfo(id, fields, _, _, rule, _)) =>
          assert(id == n)
          assert(fields.exists(_._1 == "phase"))
          assert(rule.exists(_.startsWith("wake")))
        case other => fail(s"expected a node panel, got $other")
      }
      assert(Ui.screen(apply(opened, UiEvent.SelectNode(n))).flatMap(_.panel) == None)
    }
    assert(node.nonEmpty)
  }

  test("after a hand-picked delivery, Deliver next still picks with the seed of each step") {
    val alg = Registry.echo
    val s0 = alg.defaultSpec.flatMap(spec => Session.start(alg, spec).toOption)
    val checked = s0.flatMap { s =>
      // Wake the initiator, then deliver an envelope that the seed does not pick.
      val woke = s.deliverNext
      val seeded = woke.nextChoice
      val manual = woke.sim.last.toList
        .flatMap(w => World.enabled(alg.protocol, w))
        .collectFirst { case c @ Choice.Deliver(id) if !seeded.contains(c) => (c, id) }
      manual.map { case (m, id) =>
        val after = woke.deliver(id)
        val end = LazyList.iterate(after)(_.forward).take(200).find(_.finished).getOrElse(after)
        assert(end.sim.choices.lift(1) == Some(m))
        // Each later choice is the pick of Rng(seed) advanced by the step index.
        end.sim.worlds.zip(end.sim.choices).drop(2).foreach { case (w, c) =>
          assert(
            Simulator.seededChoice(end.seed, w.step, World.enabled(alg.protocol, w)) == Some(c)
          )
        }
        assert(end.finished)
      }
    }
    assert(checked.nonEmpty)
  }

  test("regression: the status names what the node sent, and the step marks the new envelopes") {
    val woke = apply(open("echo"), UiEvent.Forward)
    val frame = Ui.screen(woke).map(_.frame)
    assert(frame.map(_.status) == Some("Node 1 ran Wake and sent Token to nodes 2, 4 and 5."))
    assert(frame.exists(f => f.envelopes.nonEmpty && f.envelopes.forall(_.fresh)))
    val next = apply(woke, UiEvent.Forward)
    val later = Ui.screen(next).map(_.frame)
    assert(later.exists(_.status.matches("Node \\d handled Token from node 1 and sent .*\\.")))
    assert(later.exists(_.envelopes.exists(!_.fresh)))
  }

  test("regression: the first status names the initiators instead of an empty sentence") {
    assert(
      Ui.screen(open("echo")).map(_.frame.status) == Some(
        "Node 1 is the initiator. No messages yet."
      )
    )
    assert(
      Ui.screen(open("chang-roberts")).map(_.frame.status) ==
        Some("Nodes 0, 2 and 3 are initiators. No messages yet.")
    )
  }

  test("regression: decisions read as plain words, not as constructor names") {
    val echoEnd = Ui.screen(runToEnd(open("echo"), 500))
    val crEnd = runToEnd(open("chang-roberts"), 500)
    val crDecided = session(crEnd).flatMap { s =>
      s.sim.run.trace.collectFirst { case TraceEvent.Decided(at, _, _) => at.step }
    }
    val atDecision = crDecided.map(step => apply(crEnd, UiEvent.Seek(step)))
    assert(
      atDecision
        .flatMap(Ui.screen)
        .exists(_.frame.status.contains("decided: it is the leader (uid 6)."))
    )
    val allEcho = session(runToEnd(open("echo"), 500)).toList.flatMap { s =>
      (0 to s.last).map(k => s.seek(k).frame(Layout.defaultSize).map(_.status).getOrElse(""))
    }
    assert(allEcho.exists(_.contains("Node 1 decided: the wave is complete.")))
    assert(echoEnd.nonEmpty)
  }

  test("regression: a link with a Script policy goes home with a notice and does not use seed 1") {
    val alg = Registry.echo
    val link = alg.defaultSpec.map { spec =>
      alg.encodeLink(SharedRun(spec.copy(policy = Policy.Script(Nil)), Nil))
    }
    val state = link.map(l => Ui.update(Ui.initial, UiEvent.Navigate(Route.Replay(l, None))))
    assert(state.flatMap(Ui.notice).exists(_.detail.contains("scripted")))
    assert(state.flatMap(session).isEmpty)
  }

  test("regression: a failed copy shows the failure, and the next event clears it") {
    val failed = apply(open("echo"), UiEvent.CopyFailed)
    assert(Ui.screen(failed).map(_.copy) == Some(CopyState.Failed))
    assert(
      Ui.screen(apply(failed, UiEvent.Resized(Size(400, 400)))).map(_.copy) == Some(
        CopyState.Failed
      )
    )
    assert(Ui.screen(apply(failed, UiEvent.Forward)).map(_.copy) == Some(CopyState.Idle))
  }

  test("a resize changes the view box to the measured size, and a phone puts labels below") {
    val phone = apply(open("chang-roberts"), UiEvent.Resized(Size(356, 490)))
    val frame = Ui.screen(phone).map(_.frame)
    assert(frame.map(f => (f.box.width, f.box.height)) == Some((356.0, 490.0)))
    val sides = frame.toList.flatMap(_.nodes.map(_.side)).toSet
    assert(sides.subsetOf(Set(LabelSide.Above, LabelSide.Below)))
    assert(frame.exists(_.nodes.exists(n => n.lines == List("asleep", "uid 4"))))
  }

  test("the node panel lists the envelopes that wait for the node") {
    val woke = apply(open("echo"), UiEvent.Forward, UiEvent.SelectNode(NodeId(2)))
    Ui.screen(woke).flatMap(_.panel) match {
      case Some(PanelView.NodeInfo(_, _, waiting, _, _, _)) =>
        assert(waiting == List("Token from node 1"))
      case other => fail(s"expected a node panel, got $other")
    }
  }

  test("the settings show the preset titles and keep the names for the link") {
    val settings = session(open("chang-roberts")).map(_.settings)
    assert(settings.map(_.presets.map(_.title)) == Some(List("Ring of 6", "Ring of 8")))
    assert(settings.flatMap(_.preset) == Some("ring-6"))
  }
}
