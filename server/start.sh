#!/bin/bash

# Start Ollama server in the background
ollama serve &

# Start ngrok to expose the Ollama server
ngrok http 5000 --url=fluent-macaw-suitably.ngrok-free.app --log=stdout &

# Start your Flask application (or any other application)
exec gunicorn -w 1 -t 4 -b 0.0.0.0:5000 gemma_main:app 

# Wait for any process to exit
wait -n

# Exit with status of process that exited first
exit $?
