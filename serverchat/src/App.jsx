import React, { useState, useEffect, useRef } from "react";
import axios from "axios";
import "./App.css"; // Optional for styling

const App = () => {
  const [userId, setUserId] = useState("user1"); // Default user
  const [message, setMessage] = useState("");
  const [chatHistory, setChatHistory] = useState({}); // Store chat history by user
  const [users, setUsers] = useState(["user1", "user2"]); // List of users
  const chatWindowRef = useRef(null);

  // Update message input
  const handleMessageChange = (e) => setMessage(e.target.value);

  // Handle sending the message
  const handleSendMessage = async () => {
    if (!message) return;

    try {
      const res = await axios.post("http://localhost:5000/chat", {
        user_id: userId,
        message,
      });

      const newResponse = res.data.response;

      // Update chat history for the current user
      setChatHistory((prevHistory) => ({
        ...prevHistory,
        [userId]: [...(prevHistory[userId] || []), { user: message, ai: newResponse }],
      }));

      setMessage(""); // Clear input
    } catch (error) {
      console.error("Error sending message:", error);
    }
  };

  // Handle pressing Enter to send the message
  const handleKeyPress = (e) => {
    if (e.key === "Enter") {
      handleSendMessage();
    }
  };

  // Handle voice input
  const handleVoiceInput = () => {
    if (!window.SpeechRecognition && !window.webkitSpeechRecognition) {
      alert("Voice recognition is not supported in this browser.");
      return;
    }

    const SpeechRecognition = window.SpeechRecognition || window.webkitSpeechRecognition;
    const recognition = new SpeechRecognition();

    recognition.lang = "en-US";
    recognition.start();

    recognition.onresult = (event) => {
      const voiceMessage = event.results[0][0].transcript;
      setMessage(voiceMessage);
      handleSendMessage(); // Automatically send the message
    };

    recognition.onerror = (event) => {
      console.error("Voice recognition error:", event.error);
    };
  };

  // Switch user and preserve chat history
  const handleSwitchUser = (newUserId) => {
    setUserId(newUserId);
  };

  // Reset user-specific context
  const handleResetContext = async () => {
    try {
      await axios.post("http://localhost:5000/reset_user_context", { user_id: userId });
      setChatHistory((prevHistory) => ({ ...prevHistory, [userId]: [] })); // Clear chat history
      alert(`Context for ${userId} has been reset.`);
    } catch (error) {
      console.error("Error resetting context:", error);
    }
  };

  // Auto-scroll the chat window to the bottom when new messages are added
  useEffect(() => {
    if (chatWindowRef.current) {
      chatWindowRef.current.scrollTop = chatWindowRef.current.scrollHeight;
    }
  }, [chatHistory]);

  return (
    <div className="app-container">
      <header>
        <h1>AI Chat Application</h1>
      </header>

      <div className="user-controls">
        <label htmlFor="user-select">Switch User:</label>
        <select
          id="user-select"
          value={userId}
          onChange={(e) => handleSwitchUser(e.target.value)}
        >
          {users.map((user) => (
            <option key={user} value={user}>
              {user}
            </option>
          ))}
        </select>
        <button onClick={handleResetContext}>Reset Context</button>
      </div>

      <div className="chat-window" ref={chatWindowRef}>
        <div className="chat-history">
          {(chatHistory[userId] || []).map((entry, index) => (
            <div key={index} className="chat-entry">
              <div className="user-message">
                <strong>You:</strong> {entry.user}
              </div>
              <div className="ai-response">
                <strong>AI:</strong> {entry.ai}
              </div>
            </div>
          ))}
        </div>

        <div className="message-input">
          <input
            type="text"
            placeholder="Type your message..."
            value={message}
            onChange={handleMessageChange}
            onKeyPress={handleKeyPress}
          />
          <button onClick={handleSendMessage}>Send</button>
          <button onClick={handleVoiceInput}>🎤 Voice Input</button>
        </div>
      </div>
    </div>
  );
};

export default App;