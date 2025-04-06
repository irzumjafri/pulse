import requests

# Base URL of your Flask server
BASE_URL = "http://127.0.0.1:5000"  # Adjust as necessary if running on a different host

def test_chat():
    print("Welcome to the Chat Testing Frontend!")
    user_id = input("Enter your user ID: ")
    
    while True:
        user_input = input("You: ")
        if user_input.lower() in ["exit", "quit"]:
            print("Exiting the chat. Goodbye!")
            break
        
        # Send a POST request to the /chat endpoint
        response = requests.post(
            f"{BASE_URL}/chat",
            json={"user_id": user_id, "message": user_input}
        )
        
        if response.status_code == 200:
            response_data = response.json()
            print(f"AI: {response_data.get('response').strip()}")
        else:
            print(f"Error: {response.status_code} - {response.text}")

if __name__ == "__main__":
    test_chat()
