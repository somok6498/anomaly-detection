#!/bin/bash
set -e

echo "=== Starting Aerospike ==="
asd --config-file /etc/aerospike/aerospike.conf

# Wait for Aerospike to be fully ready (not just responding, but node initialized)
echo "Waiting for Aerospike to be fully initialized..."
for i in $(seq 1 60); do
    # Check that the node is fully initialized by verifying namespace is available
    if asinfo -p 3000 -v "namespaces" 2>/dev/null | grep -q banking; then
        # Additional wait for node to stabilize
        sleep 2
        echo "Aerospike is ready (namespace 'banking' available)."
        break
    fi
    if [ "$i" -eq 60 ]; then
        echo "ERROR: Aerospike failed to fully initialize within 60 seconds"
        exit 1
    fi
    sleep 1
done

# Start the app with seed profile — seeds data on startup, then serves requests
# Aerospike uses in-memory storage so data is lost on restart, hence always seed
echo "=== Starting Anomaly Detection App (with auto-seeding) ==="
exec java -jar /app/app.jar --spring.profiles.active=seed
