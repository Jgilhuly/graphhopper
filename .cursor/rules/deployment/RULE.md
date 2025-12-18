---
alwaysApply: true
---
# Railway Deployment Information

## Project Details

- **Project Name:** spectacular-success
- **Project ID:** `fb3bf99d-1a6b-4c5d-85c4-ae8450f0de31`
- **Service:** graphhopper
- **Environment:** production
- **Domain:** https://graphhopper-production.up.railway.app

## Checking Deployment Status

### Prerequisites

1. Ensure Railway CLI is installed: https://docs.railway.com/guides/cli
2. Login to Railway: `railway login`
3. Link to the project: `railway link --project fb3bf99d-1a6b-4c5d-85c4-ae8450f0de31`
4. Link to the service: `railway link --service graphhopper` (or use MCP tool)

### Using Railway MCP Tools

1. **Check Railway CLI status:**
   - Use `mcp_Railway_check-railway-status` to verify CLI is installed and authenticated

2. **List deployments:**
   - Use `mcp_Railway_list-deployments` with workspacePath pointing to project root
   - Add `json: true` for structured output
   - Add `limit: 10` to limit results

3. **Get deployment logs:**
   - Use `mcp_Railway_get-logs` with:
     - `logType: "deploy"` for deployment logs
     - `logType: "build"` for build logs
     - `lines: 50` to limit output

4. **Check service status:**
   - Use `mcp_Railway_list-services` to see available services
   - Use `mcp_Railway_list-deployments` to see recent deployments

5. **Get domain information:**
   - Use `mcp_Railway_generate-domain` to get or generate domain
   - Or run `railway domain` in terminal

### Using Railway CLI Commands

```bash
# Check current status
railway status

# List recent deployments
railway deployment list

# View deployment logs
railway logs --deployment <deployment-id>

# View service logs (latest deployment)
railway logs

# Check domain
railway domain

# Link to project (if not already linked)
railway link --project fb3bf99d-1a6b-4c5d-85c4-ae8450f0de31

# Link to service
railway link --service graphhopper
```

## Deployment Configuration

- **Builder:** DOCKERFILE
- **Dockerfile:** `Dockerfile` (in project root)
- **Config File:** `railway.json`
- **Restart Policy:** ON_FAILURE (max 10 retries)
- **Region:** us-east4-eqdc4a
- **Replicas:** 1

## Service Endpoints

The GraphHopper service runs on:
- **Application Port:** 8080
- **Admin Port:** 8990
- **Public Domain:** https://graphhopper-production.up.railway.app

Available API endpoints include:
- `GET /route` - Route calculation
- `GET /isochrone` - Isochrone calculation
- `POST /match` - Map matching
- `POST /navigate` - Navigation
- `GET /health` - Health check
- `GET /info` - Service information

## Notes

- The project uses Dockerfile-based builds
- Graph cache is stored in `graph-cache/` directory
- Berlin map data (`berlin-latest.osm.pbf`) is used for routing
- Service automatically restarts on failure (up to 10 times)

