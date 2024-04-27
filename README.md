## CS553 Distributed Agorithms Project
### Submitted By -
1. Aakash Dasgupta
2. Dhruv Agarwal - 
3. Gunjan Tomar - 674659382
## Overview
This repository showcases the implementation of various distributed algorithms, including Snapshot, Wave, and Deadlock Detection. It executes distributed algorithms on graphs created by [NetGameSim](https://github.com/0x1DOCD00D/NetGameSim?tab=readme-ov-file). Made a menu driven program for 6 distributed algorithms.
### 1. Snapshot Algorithms 
The Snapshot Algorithm, refers to the process of capturing a consistent global state of the system at a specific point in time. It allows processes to record their local states and messages exchanged, facilitating the observation of the distributed system's behavior for debugging and analysis purposes.
#### a) Lai-Yang Algorithm
The Lai-Yang Algorithm is a distributed algorithm used for taking consistent global snapshots of a distributed system. It ensures that each process captures a consistent snapshot of the system's state, which is crucial for various applications like checkpointing and debugging.

#### b) Chandy-Lamport Algorithm
The Chandy-Lamport Algorithm is another method for capturing consistent global snapshots in a distributed system. It allows processes to record their local states and the messages they send and receive, facilitating the construction of a global snapshot.

### 2. Wave Algorithm
The Wave Algorithm is a communication protocol used for message dissemination and synchronization in distributed systems. It facilitates the propagation of messages through the network in a coordinated manner, ensuring reliable communication and synchronization among processes.
#### a). Echo Algorithm 
A fundamental communication protocol where a message is sent through the network and echoed back by each recipient, confirming its receipt.
#### b) Tree Algorithm
Structures the communication network in a hierarchical tree-like fashion, facilitating efficient message propagation and information dissemination.
#### c) Tarry Algorithm
Coordinates process traversal in a distributed system, ensuring a predetermined order of visitation and enabling synchronization.

### 3. Deadlock Detection
Deadlocks can occur in distributed systems when processes compete for resources and become deadlocked, unable to progress. Deadlock detection algorithms help identify and resolve such deadlocks, ensuring system stability.

#### Bracha-Toueg Algorithm
The Bracha-Toueg Algorithm is employed for deadlock detection in distributed systems. It monitors resource allocation and process interactions to detect potential deadlocks and take corrective actions to resolve them. By proactively identifying and mitigating deadlocks, this algorithm enhances the reliability and availability of distributed systems.


## About Framework
Diagram and Explanation
```bash
├── README.md
├── build.sbt
├── project
│   └── build.properties
└── src
    ├── main
    │   ├── resources
    │   │   ├── NetGraph_21-04-24-18-24-58.ngs.perturbed.dot
    │   │   ├── NetGraph_30-03-24-18-54-55.ngs.dot
    │   │   ├── application.conf
    │   │   ├── graph_50_nodes.dot
    │   │   ├── inputEcho.dot
    │   │   └── inputTarry.dot
    │   └── scala
    │       └── main
    │           ├── Main.scala
    │           ├── algorithms
    │           │   ├── BrachaTouegAlgorithm.scala
    │           │   ├── ChandyLamportAlgorithm.scala
    │           │   ├── EchoAlgorithm.scala
    │           │   ├── LaiYangAlgorithm.scala
    │           │   ├── TarrysAlgorithm.scala
    │           │   └── TreeAlgorithm.scala
    │           ├── processes
    │           │   ├── BrachaTouegProcess.scala
    │           │   ├── ChandyLamportProcess.scala
    │           │   ├── EchoProcess.scala
    │           │   ├── LaiYangProcess.scala
    │           │   ├── TarryProcess.scala
    │           │   └── TreeProcess.scala
    │           └── utility
    │               ├── ApplicationProperties.scala
    │               ├── MessageTypes.scala
    │               ├── ProcessRecord.scala
    │               ├── SnapshotUtils.scala
    │               ├── Terminator.scala
    │               └── TopologyReader.scala
    └── test
        └── scala
            ├── EchoTest.scala
            ├── TarryTest.scala
            ├── TreeTest.scala
            ├── brachaTouegTest.scala
            ├── chandyLamportTest.scala
            └── laiYangTest.scala
```
### Description of main folders

### Description of main files


## How to run project
### From Intellij IDE
Pre Requisites :
1. Java 8 or higher
2. Scala Plugin should be present

Steps to Follow :

1. Clone this repo to your local machine.
2. Open the project in Intellij
3. Navigate to src/main/scala/main/Main.scala
4. Run Main.scala file

### From Terminal
Pre Requisite :
1. Should have scala installed

Steps to Follow :
1. Clone this repo to your local machine.
2. Navigate into the Project folder
3. Run following command "**sbt clean compile run**"
   

## Test Data
Example for Tree algo from NetGameSim

## Output
Screenshot
