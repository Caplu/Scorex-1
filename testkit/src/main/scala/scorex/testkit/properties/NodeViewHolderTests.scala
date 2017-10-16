package scorex.testkit.properties

import akka.actor._
import org.scalatest.Matchers
import org.scalatest.prop.PropertyChecks
import scorex.core.LocalInterface.LocallyGeneratedModifier
import scorex.core.NodeViewHolder.EventType.{FailedPersistentModifier, SuccessfulPersistentModifier}
import scorex.core.consensus.{History, SyncInfo}
import scorex.core.transaction.box.proposition.Proposition
import scorex.core.transaction.state.MinimalState
import scorex.core.transaction.wallet.Vault
import scorex.core.transaction.{MemoryPool, Transaction}
import scorex.core.utils.ScorexLogging
import scorex.core.{NodeViewHolder, PersistentNodeViewModifier}
import scorex.testkit.generators.{SyntacticallyTargetedModifierProducer, TotallyValidModifierProducer}
import scorex.testkit.utils.{FileUtils, SequentialAkkaFixture}
import scala.concurrent.duration._


trait NodeViewHolderTests[P <: Proposition,
TX <: Transaction[P],
PM <: PersistentNodeViewModifier,
ST <: MinimalState[PM, ST],
SI <: SyncInfo,
HT <: History[PM, SI, HT],
MPool <: MemoryPool[TX, MPool],
VL <: Vault[P, TX, PM, VL]]
  extends SequentialAkkaFixture
    with Matchers
    with PropertyChecks
    with ScorexLogging
    with SyntacticallyTargetedModifierProducer[PM, SI, HT]
    with TotallyValidModifierProducer[PM, ST, SI, HT] {

  type Fixture = HolderFixture

  def nodeViewHolder(implicit system: ActorSystem): (ActorRef, PM, ST, HT)

  class HolderFixture extends AkkaFixture with FileUtils {
    val (node, mod, s, h) = nodeViewHolder
  }

  def createAkkaFixture(): Fixture = new HolderFixture

  import NodeViewHolder._

  property("NodeViewHolder: check state after creation") { ctx =>
    import ctx._
    node ! GetDataFromCurrentView[HT, ST, VL, MPool, Boolean] { v =>
      v.state.version.sameElements(s.version)
    }
    expectMsg(true)
  }

  property("NodeViewHolder: check that a valid modifier is applicable") { ctx =>
    import ctx._
    node ! GetDataFromCurrentView[HT, ST, VL, MPool, Boolean] { v =>
      v.history.applicable(mod)
    }
    expectMsg(true)
  }

  property("NodeViewHolder: check that valid modifiers are applicable") { ctx =>
    import ctx._
    node ! NodeViewHolder.Subscribe(Seq(SuccessfulPersistentModifier, FailedPersistentModifier))

    node ! GetDataFromCurrentView[HT, ST, VL, MPool, Seq[PM]] { v =>
      totallyValidModifiers(v.history, v.state, 10) //todo: fix magic number
    }
    val mods = receiveOne(5 seconds).asInstanceOf[Seq[PM]]

    mods.foreach { mod =>
      node ! LocallyGeneratedModifier(mod)
    }

    (1 to mods.size).foreach(_ => expectMsgType[SuccessfulModification[PM]])
  }

  property("NodeViewHolder: check sync info is synced") { ctx =>
    import ctx._
    node ! GetSyncInfo
    val syncInfo = CurrentSyncInfo(h.syncInfo(false))
    expectMsg(syncInfo)
  }

  property("NodeViewHolder: apply locally generated mod") { ctx =>
    import ctx._
    node ! NodeViewHolder.Subscribe(Seq(SuccessfulPersistentModifier, FailedPersistentModifier))

    val invalid = syntacticallyInvalidModifier(h)

    node ! LocallyGeneratedModifier(invalid)

    expectMsgType[FailedModification[PM]]

    node ! LocallyGeneratedModifier(mod)

    expectMsgType[SuccessfulModification[PM]]

    node ! GetDataFromCurrentView[HT, ST, VL, MPool, Boolean] { v =>
      v.state.version.sameElements(s.version) && v.history.contains(mod.id)
    }

    expectMsg(true)
  }

  property("NodeViewHolder: simple forking") { ctx =>
    import ctx._
    node ! NodeViewHolder.Subscribe(Seq(SuccessfulPersistentModifier, FailedPersistentModifier))

    node ! LocallyGeneratedModifier(mod)
    expectMsgType[SuccessfulModification[PM]]

    node ! GetDataFromCurrentView[HT, ST, VL, MPool, PM] { v =>
      totallyValidModifier(v.history, v.state)
    }
    val fork1Mod = receiveOne(5 seconds).asInstanceOf[PM]

    node ! GetDataFromCurrentView[HT, ST, VL, MPool, PM] { v =>
      totallyValidModifier(v.history, v.state)
    }
    val fork2Mod = receiveOne(5 seconds).asInstanceOf[PM]

    node ! LocallyGeneratedModifier(fork1Mod)
    node ! LocallyGeneratedModifier(fork2Mod)

    node ! GetDataFromCurrentView[HT, ST, VL, MPool, Boolean] { v =>
      v.history.contains(fork1Mod.id) || v.history.contains(fork2Mod.id)
    }
    expectMsgType[SuccessfulModification[PM]]

    expectMsg(true)
  }
}
