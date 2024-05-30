import akka.actor.ActorRef

import scala.util.Random
import scala.math.log

import java.util.UUID

// Memory-less-Confidence Agent

// Actor
class MemoryConfidenceAgent(id: UUID, stopThreshold: Float, distribution: Distribution, networkSaver: ActorRef,
                            staticAgentDataSaver: ActorRef, agentRoundDataSaver: ActorRef, networkId: UUID)
  extends DeGrootianAgent {
    // Belief related
    var publicBelief: Float = -1f
    
    // Confidence related
    var beliefExpressionThreshold: Float = -1f
    var perceivedOpinionClimate: Float = 0.0f
    var confidenceUnbounded: Float = -1f
    var confidence: Float = -1f
    var prevConfidence: Float = -1f
    
    // Belief update
    val openMindedness: Int = 50 // randomIntBetween(1, 100)
    var curInteractions: Int = 0
    
    // Process before receive from parent
    private def receive_before: Receive = {
        case setInitialState(initialBelief) =>
            belief = initialBelief
            prevBelief = belief
            publicBelief = belief
    }
    
    // Process after receive from parent
    private def receive_after: Receive = super.receive.orElse {
        case RequestBelief(roundSentFrom) =>
            sender() ! SendBelief(publicBelief, self)
        
        case SaveAgentStaticData =>
            staticAgentDataSaver ! SendStaticData(StaticAgentData(
                id, networkId, neighbors.size, tolRadius, tolOffset, Some(beliefExpressionThreshold),
                Some(openMindedness), "confidence", "memory-less", "DeGroot"
            ))
        
        case UpdateAgent(forceBeliefUpdate) =>
            prevBelief = belief
            if (confidence >= beliefExpressionThreshold) publicBelief = belief
            prevConfidence = confidence
            
            if (round == 0) {
                if (!hasUpdatedInfluences) generateInfluences()
                
                // Save Network structure
                networkSaver ! SendNeighbors(neighbors)
                
                // Save first round state
                snapshotAgentState()
            }
            round += 1
            unstashAll()
            
            fetchBeliefsFromNeighbors { beliefs =>
                var inFavor = 0
                var against = 0
                belief = 0f
                beliefs.foreach {
                    case SendBelief(neighborBelief, neighbor) =>
                        if (isCongruent(neighborBelief)) inFavor += 1
                        else against += 1
                        belief += neighborBelief * neighbors(neighbor)
                }
                
                belief += prevBelief * selfInfluence
                curInteractions += 1
                if (curInteractions == openMindedness || forceBeliefUpdate) curInteractions = 0
                else belief = prevBelief
                
                
                perceivedOpinionClimate = inFavor + against match {
                    case 0 => 0.0f
                    case totalSpeaking => (inFavor - against).toFloat / totalSpeaking
                }
                confidenceUnbounded = math.max(confidenceUnbounded + perceivedOpinionClimate, 0)
                confidence = (2f / (1f + Math.exp(-confidenceUnbounded).toFloat)) - 1f
                
                val aboveThreshold = math.abs(confidence - prevConfidence) >= stopThreshold || round == 1
                // Save the round state
                snapshotAgentState()
                network ! AgentUpdated(aboveThreshold, belief)
            }
        
    }
    
    override def receive: Receive = receive_before.orElse(super.receive).orElse(receive_after)
    
    override def preStart(): Unit = {
        distribution match {
            case Uniform =>
                belief = randomBetweenF()
                
                def reverseConfidence(c: Float): Float = {
                    if (c == 1.0) {
                        37.42994775f
                    } else {
                        -math.log(-((c - 1) / (c + 1))).toFloat
                    }
                }
                
                beliefExpressionThreshold = Random.nextFloat()
                confidence = Random.nextFloat()
                confidenceUnbounded = reverseConfidence(confidence)
            //                confidenceUnbounded = Random.nextFloat()
            //                confidence = (2 / (1 + Math.exp(-confidenceUnbounded).toFloat)) - 1
            
            case Normal(mean, std) =>
            // ToDo Implement initialization for the Normal distribution
            
            case Exponential(lambda) =>
            // ToDo Implement initialization for the Exponential distribution
            
            case BiModal(peak1, peak2, lower, upper) =>
            
            case _ =>
            
        }
    }
    
    private def snapshotAgentState(): Unit = {
        agentRoundDataSaver ! SendAgentData(RoundData(
            round, belief, confidence >= beliefExpressionThreshold, Some(confidence),
            Some(perceivedOpinionClimate), Some(publicBelief), selfInfluence, id
        ))
    }
    
}
