# FinTeen diagrams (source)

Diagram sources kept in the repo, as required for SWP490 Review 2. GitHub renders the Mermaid blocks in the `.md`
files; render the PlantUML files with `java -jar plantuml.jar <file>.puml`. The figures in the Word reports are the
draw.io versions of the same models; these sources carry the same elements.

| Model | File | Notation |
|---|---|---|
| System overview — C4 context and container | [architecture/](architecture/) | PlantUML (C4-PlantUML) |
| ERD — conceptual, logical, physical | [erd.md](erd.md) | Mermaid `erDiagram` |
| Database script matching the physical ERD | [schema.sql](schema.sql) | PostgreSQL 16 DDL |
| State machines of the main entities | [state-machines.md](state-machines.md) | Mermaid `stateDiagram-v2` |
| Activity diagrams of the 5 main workflows | [activity/](activity/) | PlantUML activity (swimlanes) |

Test traceability against these models: [../testing/test-matrix.md](../testing/test-matrix.md).
