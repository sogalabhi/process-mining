# Process mining

## It is a algorithm which analyses the flow of things. For example, amazon order to delivery, lot of things happen and lot of processes can go wrong and get repeated and cost time and money which can be analysed and fixed when we know where more wrongs are happening. 

<img width="2882" height="1802" alt="image" src="https://github.com/user-attachments/assets/ca05c8ef-4bff-4ab4-b8bd-3f4817ff1a70" />
<img width="2882" height="1802" alt="image" src="https://github.com/user-attachments/assets/cf188c40-30f9-4ba0-95b0-c3dc09d9fbc3" />

## **Example:** An order process may follow `Created → Payment → Packed → Inspected → Shipped → Delivered`, while real cases can contain skipped, repeated, or extra activities.
**How it works:** CSV event logs are ingested into PostgreSQL, transformed into chronological `CaseTrace`s, and analyzed to discover process graphs, variants, durations, bottlenecks, rework, and throughput.
**Conformance:** Each trace is compared with an expected process using dynamic-programming edit-distance alignment to identify deviations and calculate case-level and overall process fitness.

## Tech stack

1. Java spring boot
2. React

Proud to say that after a long time, built a project without using agentic ai(except frontend). Even this readme file is written wihout ai :)
