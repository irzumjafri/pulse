#!/bin/bash

# Start Ollama server in the background
ollama serve &

# Start your Flask application (or any other application)
# python main.py &pip freeze > requirements.txt
gunicorn -w 4 -b 0.0.0.0:5000 main:app &

# Start ngrok to expose the Ollama server
ngrok http 5000 --url=fluent-macaw-suitably.ngrok-free.app --log=stdout &

# Wait for any process to exit
wait -n

# Exit with status of process that exited first
exit $?
