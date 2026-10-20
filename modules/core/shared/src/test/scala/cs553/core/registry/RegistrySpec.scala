package cs553.core.registry

import cs553.core.algorithms.election.CrState
import cs553.core.algorithms.wave.EchoState
import cs553.core.ids.NodeId
import cs553.core.rng.Seed
import cs553.core.topology.Edge
import cs553.core.trace.Mark
import cs553.core.trace.Outcome
import cs553.core.trace.TraceEvent
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.immutable.SortedMap

final class RegistrySpec extends AnyFunSuite {

  test("the registry holds Echo and Chang-Roberts, each with a distinct id") {
    assert(Registry.all.map(_.id.value) == List("echo", "chang-roberts"))
    Registry.all.foreach(alg => assert(Registry.find(alg.id).map(_.id) == Some(alg.id)))
  }

  test("each preset loads, and the preset names are distinct and safe in a URL") {
    assert(Registry.echo.presets.map(_.name) == List("inputEcho", "inputTarry", "netgraph-6"))
    assert(Registry.changRoberts.presets.map(_.name) == List("ring-6", "ring-8"))
    Registry.all.foreach { alg =>
      val names = alg.algorithm.presets.map(_.name)
      assert(names.distinct == names, alg.name)
      names.foreach(n => assert(n.matches("[A-Za-z0-9-]+"), n))
    }
  }

  test("the Echo presets omit each node that has no edges") {
    Registry.echo.preset("inputEcho").foreach { preset =>
      assert(preset.topology.nodes.map(_.value).toList == List(1, 2, 3, 4, 5))
      assert(preset.topology.initiators.map(_.value).toList == List(1))
    }
  }

  test("a seeded run on each preset decides, ends quiescent and has no Rejected event") {
    Registry.all.foreach { any =>
      val alg = any.algorithm
      alg.presets.foreach { preset =>
        (1L to 5L).foreach { seed =>
          val spec = alg.spec(preset, Seed(seed))
          val result = alg.simulate(spec)
          assert(result.isRight, s"${alg.name} on ${preset.name}: $result")
          result.foreach { simulation =>
            val run = simulation.run
            val where = s"${alg.name} on ${preset.name} with seed $seed"
            assert(run.outcome == Some(Outcome.Quiescent), where)
            assert(run.decisions.nonEmpty, where)
            val rejected = run.trace.collectFirst { case TraceEvent.Rejected(_, _, _, e) => e }
            assert(rejected == None, where)
          }
        }
      }
    }
  }

  test("the default spec uses the first preset, the default seed and the protocol order") {
    Registry.all.foreach { any =>
      val alg = any.algorithm
      val spec = alg.defaultSpec
      assert(spec.map(_.topology) == alg.presets.headOption.map(_.topology))
      assert(spec.map(_.order) == Some(alg.protocol.order.default))
    }
  }

  test("a trace round-trips through the JSON codecs of the algorithm") {
    Registry.all.foreach { any =>
      val alg = any.algorithm
      alg.defaultSpec.map(alg.simulate).foreach { result =>
        result.foreach { simulation =>
          assert(alg.readTrace(alg.writeTrace(simulation.run)) == Right(simulation.run))
        }
      }
    }
  }

  test(
    "the Echo overlay marks a spanning tree at the end of a run, and Chang-Roberts has no marks"
  ) {
    Registry.echo.presets.foreach { preset =>
      val result = Registry.echo.simulate(Registry.echo.spec(preset, Seed(3)))
      assert(result.isRight, preset.name)
      result.foreach { simulation =>
        val states = simulation.last.map(_.states).getOrElse(SortedMap.empty[NodeId, EchoState])
        val marks = Registry.echo.overlay.marks(states)
        val nodes = preset.topology.nodes
        assert(marks.size == nodes.size - 1, preset.name)
        val children = marks.collect { case Mark.TreeEdge(child, parent) =>
          assert(preset.topology.edges.contains(Edge(child, parent)), preset.name)
          child
        }
        assert(children.distinct.size == children.size, preset.name)
      }
    }
    Registry.changRoberts.defaultSpec.map(Registry.changRoberts.simulate).foreach { result =>
      result.foreach { simulation =>
        val states = simulation.last.map(_.states).getOrElse(SortedMap.empty[NodeId, CrState])
        assert(Registry.changRoberts.overlay.marks(states) == Nil)
      }
    }
  }
}
