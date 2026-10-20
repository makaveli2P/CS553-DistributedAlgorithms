package cs553.core.lessons

import cs553.core.ids.AlgorithmId
import cs553.core.ids.NodeId
import cs553.core.rng.Seed

/** One narration step of a lesson. The UI shows the world after `step` steps of the run and the
  * one-sentence `text` under the canvas.
  */
final case class Beat(step: Int, text: String)

/** How a test finds the right answer of a question in the finished run. The test compares the
  * result with `Question.answer`, so the data cannot drift from the algorithm.
  */
enum Truth {

  /** The node of the first `Decided` event. */
  case Decider

  /** The node that has the first `Cut` mark of the overlay. */
  case FirstToRecord

  /** The node that has the last first `Cut` mark of the overlay. */
  case LastToRecord
}

/** The one prediction question at the end of a lesson. The learner answers it with a click on a
  * node. `step` is the step that the canvas shows while the question is open.
  */
final case class Question(
    step: Int,
    text: String,
    answer: NodeId,
    truth: Truth,
    because: String
) {
  def right: String = s"Yes. $because"
  def wrong: String = "Not this node. Look at the canvas and try again."
}

/** A guided lesson: a fixed seeded run of one preset, a few beats and one question. The lesson is
  * pure data. `Algorithm.spec(preset, seed)` gives the run spec.
  */
final case class Lesson(
    algorithm: AlgorithmId,
    preset: String,
    seed: Seed,
    beats: List[Beat],
    question: Question
) {

  /** The number of screens: each beat, and then the question. */
  def size: Int = beats.size + 1

  /** The step that the canvas shows on screen `index`. */
  def stepAt(index: Int): Int = beats.lift(index).fold(question.step)(_.step)

  /** The sentence that screen `index` shows. */
  def textAt(index: Int): String = beats.lift(index).fold(question.text)(_.text)
}

/** The lessons of the eight algorithms. */
object Lessons {

  private val seed: Seed = Seed(1)

  private def lesson(id: String, preset: String)(beats: Beat*)(question: Question): Lesson =
    Lesson(AlgorithmId(id), preset, seed, beats.toList, question)

  val all: List[Lesson] = List(
    lesson("chandy-lamport", "bank-4")(
      Beat(0, "Four nodes keep money. They send money in messages."),
      Beat(5, "Money on a channel belongs to no node until it arrives."),
      Beat(12, "Money still moves. No node has a snapshot yet."),
      Beat(20, "A node saves its balance when its first marker arrives. Then it sends markers on.")
    )(
      Question(
        25,
        "Which node started the snapshot?",
        NodeId(0),
        Truth.FirstToRecord,
        "Node 0 saved its balance first and sent the first markers."
      )
    ),
    lesson("lai-yang", "bank-4")(
      Beat(0, "Nodes send white messages. No node has a snapshot yet."),
      Beat(4, "One node starts a snapshot. It saves its balance and turns red."),
      Beat(8, "A red node sends red messages."),
      Beat(11, "A node that gets a red message saves its balance and turns red too.")
    )(
      Question(
        25,
        "Which node saved its balance last?",
        NodeId(3),
        Truth.LastToRecord,
        "Node 3 turned red after all the other nodes."
      )
    ),
    lesson("echo", "inputEcho")(
      Beat(0, "Node 1 is the initiator. All other nodes wait."),
      Beat(1, "Node 1 sends a token to each neighbour."),
      Beat(4, "A node that gets its first token calls the sender its parent."),
      Beat(9, "A node echoes to its parent after all its neighbours answered."),
      Beat(13, "A node with no other neighbour echoes at once.")
    )(
      Question(
        14,
        "Which node decides when the wave is done?",
        NodeId(1),
        Truth.Decider,
        "The initiator decides after all the echoes arrive."
      )
    ),
    lesson("tarry", "inputTarry")(
      Beat(0, "Node 1 is the initiator. One token moves through the network."),
      Beat(2, "A node sends the token to one neighbour at a time."),
      Beat(9, "A node that gets the token back sends it to the next neighbour."),
      Beat(12, "A node with no neighbour left sends the token to its parent."),
      Beat(22, "Node 1 sends the token to each of its neighbours in turn.")
    )(
      Question(
        28,
        "Which node decides that the traversal is complete?",
        NodeId(1),
        Truth.Decider,
        "The token comes back to the initiator, after it visited all nodes."
      )
    ),
    lesson("tree", "tree-7")(
      Beat(0, "Only the leaves start. Each leaf has one neighbour."),
      Beat(3, "Each leaf sends one token to its neighbour."),
      Beat(5, "A node sends a token on after it got tokens from all neighbours but one."),
      Beat(8, "Node 1 got tokens from two neighbours. It sends a token to the third.")
    )(
      Question(
        9,
        "Which node decides first?",
        NodeId(4),
        Truth.Decider,
        "Node 4 gets the token from node 1 after it sent its own token, so it decides."
      )
    ),
    lesson("bracha-toueg", "mixed-6")(
      Beat(0, "Node 0 wants to know if it is deadlocked. It waits for other nodes."),
      Beat(1, "Node 0 sends a notify message to each node that it waits for."),
      Beat(5, "Node 4 waits for no node. It sends a grant at once."),
      Beat(13, "A node that gets enough grants can go on. Then it grants its own waiters.")
    )(
      Question(
        22,
        "Which node learns that it is not deadlocked?",
        NodeId(0),
        Truth.Decider,
        "Node 0 got a grant, so it is free."
      )
    ),
    lesson("chang-roberts", "ring-6")(
      Beat(0, "Each node has a uid. Messages go around the ring in one direction."),
      Beat(1, "Node 3 wakes and sends its uid to the next node."),
      Beat(5, "A node drops a uid that is smaller than its own uid."),
      Beat(9, "Uid 6 is the largest uid. It passes each node.")
    )(
      Question(
        10,
        "Which node becomes the leader?",
        NodeId(2),
        Truth.Decider,
        "Node 2 has the largest uid, 6, and its own uid comes back to it."
      )
    ),
    lesson("franklin", "ring-8")(
      Beat(0, "Each node has a uid. Messages go in both directions."),
      Beat(1, "A node that wakes sends its uid to both neighbours."),
      Beat(12, "A node that gets a bigger uid than its own uid becomes passive."),
      Beat(18, "A passive node only passes messages on."),
      Beat(34, "In each round, fewer nodes stay active.")
    )(
      Question(
        51,
        "Which node becomes the leader?",
        NodeId(3),
        Truth.Decider,
        "Node 3 has the largest uid, 8, so it stays active in each round."
      )
    )
  )

  def find(id: AlgorithmId): Option[Lesson] = all.find(_.algorithm == id)
}
