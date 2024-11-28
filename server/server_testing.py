import requests
import json

# The base URL of the Flask app
BASE_URL = "http://127.0.0.1:5000"

# Function to test sending a chat message
def test_chat(user_id, message):
    url = f"{BASE_URL}/chat"
    data = {
        "user_id": user_id,
        "message": message
    }
    response = requests.post(url, json=data)
    if response.status_code == 200:
        print(f"Response: {response.json()['response']}")
    else:
        print(f"Error: {response.text}")

# Function to test setting the global context
def test_set_global_context(new_context):
    url = f"{BASE_URL}/set_global_context"
    data = {
        "context": new_context
    }
    response = requests.post(url, json=data)
    if response.status_code == 200:
        print(f"Global context updated: {response.json()['message']}")
    else:
        print(f"Error: {response.text}")

# Function to test resetting the user context
def test_reset_user_context(user_id):
    url = f"{BASE_URL}/reset_user_context"
    data = {
        "user_id": user_id
    }
    response = requests.post(url, json=data)
    if response.status_code == 200:
        print(f"User context reset: {response.json()['message']}")
    else:
        print(f"Error: {response.text}")

if __name__ == "__main__":
    # Test setting a new global context
    test_set_global_context("This is a new global context for Pulse AI.")

    # Test chatting with the AI
    user_id = "user_1"
    test_chat(user_id, "What is the status of patient X?")

    # Test resetting user context
    test_reset_user_context(user_id)

    # Test chatting with the AI again to see if user context was reset
    test_chat(user_id, "What is the status of patient Y?")
