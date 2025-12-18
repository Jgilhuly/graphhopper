# GraphHopper Architecture Overview

## Introduction

GraphHopper is a fast and memory-efficient routing engine that calculates routes, distances, turn-by-turn instructions, and road attributes between multiple points. It can be used as a Java library or standalone web server.

## High-Level Architecture

```mermaid
graph TB
    subgraph "Client Layer"
        Web[Web Browser]
        Mobile[Mobile Apps]
        API_Client[API Clients]
    end

    subgraph "Application Layer"
        App[GraphHopperApplication<br/>Dropwizard]
        Config[GraphHopperServerConfiguration]
    end

    subgraph "Web API Layer"
        Root[RootResource]
        Route[RouteResource]
        Isochrone[IsochroneResource]
        Match[MapMatchingResource]
        Navigate[NavigateResource]
        PT[PtRouteResource]
        SPT[SPTResource]
        Health[HealthCheckResource]
    end

    subgraph "Core Routing Engine"
        GH[GraphHopper]
        Router[Router]
        Solver[Routing Solvers]
        CH[Contraction Hierarchies]
        LM[Landmarks]
    end

    subgraph "Data Layer"
        BaseGraph[BaseGraph]
        LocationIndex[LocationIndex]
        EncodingManager[EncodingManager]
        Profiles[Routing Profiles]
    end

    subgraph "Data Processing"
        OSMReader[OSMReader]
        WayParser[WaySegmentParser]
        PostProcess[Post Processing]
    end

    subgraph "Storage"
        GraphCache[graph-cache/<br/>Graph Files]
        OSMFile[OSM PBF Files]
    end

    subgraph "Specialized Modules"
        MapMatch[Map Matching]
        Navigation[Navigation Module]
        GTFS[GTFS Reader]
    end

    Web --> App
    Mobile --> App
    API_Client --> App

    App --> Config
    App --> Root
    Root --> Route
    Root --> Isochrone
    Root --> Match
    Root --> Navigate
    Root --> PT
    Root --> SPT
    Root --> Health

    Route --> GH
    Isochrone --> GH
    Match --> MapMatch
    Navigate --> Navigation
    PT --> GTFS
    SPT --> GH

    GH --> Router
    Router --> Solver
    Solver --> CH
    Solver --> LM
    Solver --> BaseGraph

    GH --> BaseGraph
    GH --> LocationIndex
    GH --> EncodingManager
    GH --> Profiles

    OSMFile --> OSMReader
    OSMReader --> WayParser
    WayParser --> BaseGraph
    BaseGraph --> PostProcess
    PostProcess --> GraphCache
    GraphCache --> GH

    MapMatch --> GH
    Navigation --> GH
    GTFS --> GH

    style App fill:#4a90e2,color:#fff
    style GH fill:#50c878,color:#fff
    style BaseGraph fill:#ff6b6b,color:#fff
    style OSMReader fill:#ffa500,color:#fff
```

## Request Flow Architecture

```mermaid
sequenceDiagram
    participant Client
    participant App as GraphHopperApplication
    participant Resource as RouteResource
    participant Router as Router
    participant Solver as Routing Solver
    participant Graph as BaseGraph
    participant Index as LocationIndex

    Client->>App: HTTP GET /route?point=...
    App->>Resource: Route request
    Resource->>Resource: Parse parameters<br/>(points, profile, algorithm)
    Resource->>Router: route(GHRequest)
    Router->>Router: Validate request
    Router->>Index: findClosest(points)
    Index-->>Router: node IDs
    Router->>Solver: createSolver(request)
    alt Speed Mode (CH)
        Solver->>Graph: Query CH graph
    else Hybrid Mode (LM)
        Solver->>Graph: Query with Landmarks
    else Flexible Mode
        Solver->>Graph: Dijkstra/A* search
    end
    Graph-->>Solver: Path result
    Solver->>Router: Path with edges
    Router->>Router: Calculate instructions<br/>Path details<br/>Elevation
    Router-->>Resource: GHResponse
    Resource->>Resource: Format response<br/>(JSON/GPX/XML)
    Resource-->>App: HTTP Response
    App-->>Client: Route data
```

## Data Processing Pipeline

```mermaid
flowchart LR
    subgraph "Input"
        OSM[OSM PBF File<br/>berlin-latest.osm.pbf]
        GTFS_Data[GTFS Data<br/>Optional]
    end

    subgraph "Import Phase"
        OSMReader[OSMReader]
        WayParser[WaySegmentParser]
        TagParsers[Tag Parsers<br/>Road type, speed, etc.]
        RelationProc[Relation Processor<br/>Turn restrictions]
    end

    subgraph "Graph Construction"
        BaseGraph[BaseGraph<br/>Nodes & Edges]
        Encoding[EncodingManager<br/>Vehicle profiles]
        Elevation[Elevation Data<br/>Optional]
    end

    subgraph "Post Processing"
        Subnetworks[Subnetwork<br/>Extraction]
        CHPrep[CH Preparation<br/>Contraction Hierarchies]
        LMPrep[LM Preparation<br/>Landmarks]
        LocationIdx[Location Index<br/>Spatial Index]
    end

    subgraph "Output"
        Cache[graph-cache/<br/>Persistent Storage]
    end

    OSM --> OSMReader
    GTFS_Data --> OSMReader
    OSMReader --> WayParser
    WayParser --> TagParsers
    TagParsers --> RelationProc
    RelationProc --> BaseGraph
    BaseGraph --> Encoding
    BaseGraph --> Elevation
    Encoding --> Subnetworks
    Subnetworks --> CHPrep
    Subnetworks --> LMPrep
    BaseGraph --> LocationIdx
    CHPrep --> Cache
    LMPrep --> Cache
    LocationIdx --> Cache
    BaseGraph --> Cache

    style OSM fill:#ff6b6b,color:#fff
    style BaseGraph fill:#50c878,color:#fff
    style Cache fill:#4a90e2,color:#fff
```

## Module Dependencies

```mermaid
graph TD
    subgraph "Web Layer"
        Web[web<br/>Main Application]
        WebAPI[web-api<br/>API Definitions]
        WebBundle[web-bundle<br/>REST Resources]
    end

    subgraph "Core Layer"
        Core[core<br/>Routing Engine]
    end

    subgraph "Specialized Modules"
        MapMatch[map-matching<br/>Map Matching]
        Nav[navigation<br/>Navigation]
        GTFS[reader-gtfs<br/>Public Transit]
    end

    subgraph "Supporting"
        Client[client-hc<br/>Java Client]
        Example[example<br/>Examples]
        Tools[tools<br/>Utilities]
    end

    Web --> WebBundle
    Web --> WebAPI
    WebBundle --> Core
    WebAPI --> Core
    MapMatch --> Core
    Nav --> Core
    GTFS --> Core
    Client --> WebAPI
    Example --> Core
    Tools --> Core

    style Core fill:#50c878,color:#fff
    style Web fill:#4a90e2,color:#fff
```

## Routing Modes

```mermaid
graph LR
    subgraph "Routing Modes"
        Speed[Speed Mode<br/>Contraction Hierarchies<br/>Fastest, Pre-computed]
        Hybrid[Hybrid Mode<br/>Landmarks + CH<br/>Flexible, Fast]
        Flexible[Flexible Mode<br/>Dijkstra/A*<br/>Most Flexible, Slower]
    end

    subgraph "Characteristics"
        Speed --> SpeedChar[• Fastest queries<br/>• Pre-defined profiles<br/>• Requires CH prep<br/>• Less flexible]
        Hybrid --> HybridChar[• Fast queries<br/>• Some flexibility<br/>• Requires LM prep<br/>• Supports custom models]
        Flexible --> FlexChar[• Slower queries<br/>• Full flexibility<br/>• No prep needed<br/>• Custom models OK]
    end

    style Speed fill:#50c878,color:#fff
    style Hybrid fill:#ffa500,color:#fff
    style Flexible fill:#ff6b6b,color:#fff
```

## API Endpoints

```mermaid
graph TB
    subgraph "Core Routing"
        Route[/route<br/>Calculate route between points]
        Isochrone[/isochrone<br/>Calculate reachable areas]
        SPT[/spt<br/>Shortest path tree]
    end

    subgraph "Specialized"
        Match[/match<br/>Map matching - snap GPX to roads]
        Navigate[/navigate<br/>Mobile navigation]
        PTRoute[/route-pt<br/>Public transit routing]
        PTIsochrone[/isochrone-pt<br/>PT reachable areas]
    end

    subgraph "Utilities"
        Nearest[/nearest<br/>Find nearest point on road]
        Info[/info<br/>Service information]
        Health[/health<br/>Health check]
        I18N[/i18n<br/>Translations]
        MVT[/mvt<br/>Vector tiles]
    end

    style Route fill:#4a90e2,color:#fff
    style Match fill:#50c878,color:#fff
    style Navigate fill:#ffa500,color:#fff
```

## Component Details

### Core Components

#### GraphHopper
- Main entry point for routing operations
- Manages graph loading and initialization
- Coordinates between different modules

#### BaseGraph
- In-memory graph data structure
- Stores nodes (coordinates) and edges (road segments)
- Supports 2D and 3D (with elevation) graphs
- Memory-mapped for efficient access

#### Router
- Handles routing requests
- Validates input parameters
- Creates appropriate solver based on routing mode
- Calculates turn instructions and path details

#### LocationIndex
- Spatial index for finding nearest points on graph
- Used for snapping coordinates to road network
- Tree-based structure for fast lookups

#### EncodingManager
- Manages vehicle profiles (car, bike, foot, etc.)
- Encodes road attributes (speed, access, surface, etc.)
- Handles custom models for flexible routing

### Data Flow

1. **Import Phase**: OSM PBF files are read and parsed into graph structure
2. **Post-Processing**: Graph is optimized with CH/LM preparations
3. **Storage**: Processed graph is stored in `graph-cache/` directory
4. **Runtime**: Graph is loaded into memory for routing queries

### Key Algorithms

- **Contraction Hierarchies (CH)**: Pre-computes shortcuts for ultra-fast routing
- **Landmarks (LM)**: Uses A* with landmarks for fast flexible routing
- **Dijkstra/A***: Standard shortest path algorithms for maximum flexibility

## Deployment Architecture

```mermaid
graph TB
    subgraph "Deployment"
        Docker[Docker Container]
        Railway[Railway Platform]
    end

    subgraph "Application"
        JVM[JVM Java 17+]
        Dropwizard[Dropwizard Server]
        Port8080[Port 8080<br/>Application]
        Port8990[Port 8990<br/>Admin]
    end

    subgraph "Resources"
        GraphCache[graph-cache/<br/>Persistent]
        OSMData[OSM PBF Files]
        Config[config-railway.yml]
    end

    Docker --> Railway
    Railway --> JVM
    JVM --> Dropwizard
    Dropwizard --> Port8080
    Dropwizard --> Port8990
    Dropwizard --> GraphCache
    Dropwizard --> OSMData
    Dropwizard --> Config

    style Railway fill:#4a90e2,color:#fff
    style Dropwizard fill:#50c878,color:#fff
```

## Technology Stack

- **Language**: Java 17+
- **Framework**: Dropwizard (Jersey REST, Jetty server)
- **Build Tool**: Maven
- **Data Structures**: HPPC (High Performance Primitive Collections)
- **Spatial**: JTS (Java Topology Suite)
- **Storage**: Memory-mapped files for graph data
- **Deployment**: Docker, Railway

## Performance Characteristics

- **Speed Mode**: Sub-millisecond routing queries
- **Hybrid Mode**: Millisecond-level queries with flexibility
- **Flexible Mode**: Slower but fully customizable
- **Memory**: Efficient data structures, memory-mapped files
- **Scalability**: Supports small indoor to world-wide graphs

