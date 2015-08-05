Message Delivery
=========================
This demo explains how to guarantee message delivery in the cases where losing messages is considered costly.

 The sender of messages is a FSM actor who can not make any progress until it makes sure that each message has been successfully delivered.
 This actor will send a message, and then will change its state to waiting for ack. if the message has not been receive before the
 timeout, the same message will be sent again. This propcess will be repeated until Ack message is received from Receiver.

 This scenario is accompanied with some issues that need to be taken care of

   - what if the same message has been relieved by the Receiver more than once.
      Both Sender and Reciever should keep track of the sequence of distinct sent and processed message respectively. On Receiver side
      when the message first received it will be processed normally, and the sequence will be increased by one. If the same message received
      again it will have sequence less than the current sequence which means that message already processed, and we need only to
      send the Ack.
   - what if the Ack lost on the way
      if the message go lost on the way, the Sender after the timeout will resend the same message again until the ack is received.

   - what if an old ack received while we are waiting for newer one.
      The sender will be waiting for ack with particulare seq. If Ack for different seq received it will be ignored and the sender will
      stay in waiting state.




To demonstrate, run $ sbt test-only WorkRunnerTest. This takes the WorkRunner FSM through all three scenarios:

- Sender will keep resending the same message until it receives Ack message
- Sender will ignore the ack that belong to already received ack
- Receiver will Send Ack immediately for old seq