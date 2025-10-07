## 4.4.1.2 Workflow Engine


	The process begins outside the flowchart. At startup, the Workflow Engine parses all .yml files and subscribes to the Communication Fabric for every unique trigger.event_type it finds. The flowchart above begins when one of these triggers is activated. 

	When the Communication Fabric delivers an event that matches one of the engine’s subscriptions, it creates a workflow instance. The engine does not process the logic globally, it creates a new, stateful workflowinstance object in memory. This object is responsible for tracking the progress and all context variables for this single, specific run. 

	The instance now enters a loop. The engine checks if it has reached the end of the step list in the .yml file. If not, it proceeds. The engine reads the next item from the workflow’s steps list and determines if it is an immediate ‘action’ or a stateful ‘wait_for’.

	When processing an ‘action’ step, the engine prepares a new Event object as defined in the .yml. It uses the data stored in the instance’s context to populate variables, for example, substituting trigger.data.sourceIp with the actual IP address. It then publishes this new event to the Communication Fabric. After publishing, the action is complete. The engine loops back to process the next step in the sequence. 
	
	When processing a ‘wait_for’ step, the workflow instance is marked as PAUSED. It will no longer execute steps sequentially. The engine programmatically creates a new, temporary subscription on the Communication Fabric for the event_type specified in the wait_for block. It also creates a callback function that will fire if a matching event arrives. A timeout clock is then started, as defined in the .yml. The instance waits for two possible outcomes: one, the event arrives. The engine checks if the data in the newly arrived event meets all the specified conditions. If the conditions match, the new event’s data is added to the instance’s context, the instance becomes ACTIVE again, and the engine loops back to process the next step. If the conditions do not match, it ignores the event and continues waiting. Two, the awaited event did not arrive in time. The workflow has failed. The engine cleans up the temporary subscription and terminates this instance, marking it as a Failure. 

	If the engine reaches the end of the step list, the workflow has executed successfully. The instance is destroyed. If a timeout occurs, the workflow terminates unsuccessfully. 
