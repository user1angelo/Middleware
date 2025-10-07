## 4.4.1.3 Module Registry & Lifecycle Manager

	To better understand how the Module Registry and Lifecycle Manager works, we need to look at how it starts. 

	This phase is executed once the main application starts. The goal is to find, validate, and activate all available external modules. 

	The process begins by scanning a predefined modules directory on the file system. It identifies all files with a .jar extension and compiles them into a list for processing. The manager iterates through each discovered .jar file one by one.

	The “plug-and-play” mechanism starts with a special URLClassLoader, this class makes the code inside the current JAR file visible to the running application at runtime. The manager then uses Java Reflection to programmatically inspect the contents of the JAR, searching for a class that implements the PluggableModule interface from the SDK. This step is crucial as it is how the manager verifies that the JAR contains a valid entry point that adheres to the framework’s contract. 

	If no class implementing PluggableModuleis found, the JAR is considered invalid. A warning is logged, and the manager moves to the next file. If a valid class is found, the manager creates a new object instance of that class. 

	The manager calls the initialize() method on the newly created module instance. The manager then passes the CoreSystemAPI handle to the module. This handle is the module’s key to the communication fabric, allowing it to publish and subscribe to events. 

	If initialize throws an error, the module is considered faulty, an error is logged, and the instance is discarded. If initialization succeeds, the module instance is added to an internal list of active, running modules, and a success message is logged. 

	After processing all JAR files, the startup is complete. The system now has a registry of all successfully loaded and running modules. 

	
	This phase is executed once when the main application receives a signal to terminate. Its goal is to ensure a clean and graceful shutdown of all external components.

	The process begins by getting the list of all active modules that were successfully loaded during startup. The manager then iterates through each module in the registry.

	For each module, the manager calls its shutdown() method. This is the module’s opportunity to release any resources it holds, such as database connections, open files, or network sockets. The process is designed to be robust. If one module fails to shut down correctly, an error is logged, but the manager continues to the next module to ensure that all other components get a chance to terminate clearly. 
	After attempting to shut down all modules, the manager’s role is complete, and the main application can safely exit. 
