# Required imports
# from langdetect import detect
import os
from flask import Flask, request, jsonify
from flask_cors import CORS
from langchain_ollama import OllamaLLM
from langchain_core.prompts import ChatPromptTemplate
import pandas as pd
import time

# Initialize Flask app
app = Flask(__name__)
CORS(app)  # Enable CORS for all origins

# Initialize model and prompt templates
chatTemplate = """
Answer the question below, prioritizing information from the provided context.  If you must provide information outside the context, explicitly state that it is not from the provided data.  Do not fabricate information.
Current Time: {current_time}

Context: {context}

Patient Details: {patient_details}

Nurse Notes: {nurse_notes_result}

Question: {question}

Instructions:

*   Be concise and clear in your answer.  Avoid any details that might not be useful to your fellow nurse.
*   Start by addressing any urgent alerts or recent updates (based on the provided timestamps).
*   Do not repeat information already present in the context unless specifically asked to.
*   Answer in a natural, human-like conversational style.
*   Do not include dates or timestamps in your response, unless specifically requested.
*   Do not use emojis.
*   Focus on the most relevant information for the question.

Answer:
"""

recordingTemplate = """
Answer the Question Below. If you are providing patient details, only provide information that can be found in the context and if you are providing something outside the context, mention that clearly.

Here is the conversation history: {context}

Here are the patient details: {patient_details}

Here is the patient note: {patient_note}

Confirm that the patient note has been recorded successfully and generate a confirmation to keep the flow of the conversation.

Response:
"""

nurseNotesTemplate = """
Answer the Question Below. If you are providing patient details, only provide information that can be found in the context and if you are providing something outside the context, mention that clearly.

Here are the nurse notes: 
{nurse_notes}

Given the list of these notes, group the notes by a common theme, and based on the date, provide the latest update for a particular theme. Don't provide any reasoning.
Response:
"""

model = OllamaLLM(model="irzumbm/pulseAITiny", max_tokens=30)
chatPrompt = ChatPromptTemplate.from_template(chatTemplate)
recordPrompt = ChatPromptTemplate.from_template(recordingTemplate)
nurseNotesPrompt = ChatPromptTemplate.from_template(nurseNotesTemplate)
chatChain = chatPrompt | model
nurseNotesChain = nurseNotesPrompt | model
recordChain = recordPrompt | model

# Context storage: global and user-specific
global_context = ""
user_contexts = {}

# Load patient data
patient_data_path = "patient_data.csv"  # Path to the patient data file
patient_data = pd.read_csv(patient_data_path)

def get_patient_notes(patient_id):
    try:
        nurse_notes = pd.read_csv("nurse_notes.csv")
        print(f"Processing nurse notes for Patient ID: {patient_id}")
    except FileNotFoundError:
        return [{"date": "N/A", "note": "No notes file available."}]
    except Exception as e:
        return [{"date": "N/A", "note": f"Error reading nurse_notes.csv: {e}"}]

    if "PatientID" not in nurse_notes.columns:
        return [{"date": "N/A", "note": "No PatientID found in notes."}]

    notes = nurse_notes[nurse_notes["PatientID"] == patient_id]
    if notes.empty:
        return [{"date": "N/A", "note": "No notes available for this patient."}]

    notes_list = [
        {"date": row["Date"], "note": row["Note"]}
        for _, row in notes.iterrows()
    ]

    if not notes_list:
        return [{"date": "N/A", "note": "No notes available to process."}]

    try:
        print("Processing nurse notes using AI...")
        nurse_start_time = time.time()
        nurse_notes_result = nurseNotesChain.invoke({"nurse_notes": notes_list})
        print(f"Nurse notes processed for Patient ID: {patient_id}")
        print(nurse_notes_result)
        nurse_end_time = time.time()
        print(f"Nurse Notes AI Processing time: {nurse_end_time - nurse_start_time} seconds")
        return nurse_notes_result
    except Exception as e:
        return [{"date": "N/A", "note": f"Error processing nurse notes: {e}"}]

def get_patient_details_by_room(room_number):
    patient_info = patient_data[patient_data["Room Number"] == room_number]
    if not patient_info.empty:
        patient = patient_info.iloc[0]
        patient_id = patient["Patient ID"]
        print(f"Patient detected by room: {room_number}, Name: {patient['Patient Name']}")
        return (
            f"Patient Name: {patient['Patient Name']}, Room Number: {patient['Room Number']}, "
            f"Condition: {patient['Condition']}, Diagnosis: {patient['Diagnosis']}, "
            f"Undergoing Treatments: {patient['Undergoing Treatments']}, Assigned Nurse: {patient['Assigned Nurse ID']}, Last Update: {patient['Last Update']}, "
            f"Patient Care Details: {patient['Patient Care Details']}"
        ), patient_id
    return None, None

def get_patient_details_by_name(patient_name):
    patient_info = patient_data[patient_data["Patient Name"].str.contains(patient_name, case=False, na=False)]
    if not patient_info.empty:
        patient = patient_info.iloc[0]
        patient_id = patient["Patient ID"]
        print(f"Patient detected by name: {patient_name}")
        return (
            f"Patient Name: {patient['Patient Name']}, Room Number: {patient['Room Number']}, "
            f"Condition: {patient['Condition']}, Diagnosis: {patient['Diagnosis']}, "
            f"Undergoing Treatments: {patient['Undergoing Treatments']}, Assigned Nurse: {patient['Assigned Nurse ID']}, Last Update: {patient['Last Update']}, "
            f"Patient Care Details: {patient['Patient Care Details']}"
        ), patient_id
    return None, None

@app.route('/chat', methods=['POST'])
def chat():
    start_time = time.time()
    data = request.json
    user_id = data.get("user_id")
    user_input = data.get("message")
    user_language = data.get("language")

    print("Request received from user:", user_id)
    print("Message:", user_input)
    print("Language:", user_language)

    current_global_context = global_context + f"\nRespond in {'Finnish' if user_language == 'fi' else 'English'}."

    # # Detect language
    # try:
    #     language = detect(user_input)
    #     current_global_context = global_context + f"\nRespond in {'Finnish' if language == 'fi' else 'English'}."
    # except Exception as e:
    #     return jsonify({"error": f"Language detection failed: {str(e)}"}), 500

    if user_id not in user_contexts:
        user_contexts[user_id] = {
            "patient_id": None,
            "patient_details": None,
            "nurse_notes": [],
            "chat_history": []
        }

    context = current_global_context

    # Identify patient from the user input (by room number or name)
    patient_details, patient_id = None, None
    for room_number in patient_data["Room Number"].unique():
        if str(room_number) in user_input:
            patient_details, patient_id = get_patient_details_by_room(room_number)
            if patient_details:
                break

    if not patient_details:
        for name in patient_data["Patient Name"]:
            if name.lower() in user_input.lower():
                patient_details, patient_id = get_patient_details_by_name(name)
                if patient_details:
                    break

    # Reset or update the user context if a new patient is detected
    if patient_details:
        if user_contexts[user_id]["patient_id"] is not None and user_contexts[user_id]["patient_id"] != patient_id:
            print("New patient detected. Resetting user context.")
            user_contexts[user_id] = {
                "patient_id": patient_id,
                "patient_details": patient_details,
                "nurse_notes": get_patient_notes(patient_id),
                "chat_history": []  # Reset chat history as well
            }
        else:
            user_contexts[user_id]["patient_id"] = patient_id
            user_contexts[user_id]["patient_details"] = patient_details
            user_contexts[user_id]["nurse_notes"] = get_patient_notes(patient_id)

    context += "\nChat History:\n" + "\n".join(
        [f"User: {entry['user']}\nAI: {entry['ai']}" for entry in user_contexts[user_id]["chat_history"]]
    )

    print("Calling chat chain...")
    ai_start_time = time.time()
    current_time = time.strftime("%Y-%m-%d %H:%M:%S", time.localtime())
    chat_result = chatChain.invoke({
        "current_time": current_time,
        "context": context,
        "question": user_input,
        "patient_details": user_contexts[user_id]["patient_details"],
        "nurse_notes_result": user_contexts[user_id]["nurse_notes"]
    })
    print("Chat chain response received.")
    ai_end_time = time.time()
    print(f"Chat Response time: {ai_end_time - ai_start_time} seconds")

    user_contexts[user_id]["chat_history"].append({"user": user_input, "ai": chat_result})

    end_time = time.time()
    print(f"Total Response time: {end_time - start_time} seconds")

    return jsonify({"response": chat_result})

@app.route('/record', methods=['POST'])
def record():
    data = request.json
    user_id = data.get("user_id")
    patient_note = data.get("message")
    current_date = pd.to_datetime("now").strftime("%Y-%m-%d %H:%M:%S")

    print("Request received from user:", user_id)
    print("Message:", patient_note)
    print("Language:", data.get("language"))

    if not patient_note:
        return jsonify({"error": "Patient data couldn't be saved due to missing note content."}), 400

    # Ensure user context exists
    if user_id not in user_contexts:
        user_contexts[user_id] = {
            "patient_id": None,
            "patient_details": None,
            "nurse_notes": [],
            "chat_history": []
        }

    # Check if a patient is already in the user's context
    if user_contexts[user_id]["patient_id"]:
        patient_id = user_contexts[user_id]["patient_id"]
        patient_details = user_contexts[user_id]["patient_details"]
        print(f"Patient already in context: {patient_details}")
    else:
        # Step 1: Extract potential patient information from the note
        potential_patient_details, potential_patient_id = None, None
        for room_number in patient_data["Room Number"].unique():
            if str(room_number) in patient_note:
                potential_patient_details, potential_patient_id = get_patient_details_by_room(room_number)
                if potential_patient_details:
                    break

        if not potential_patient_details:
            for name in patient_data["Patient Name"]:
                if name.lower() in patient_note.lower():
                    potential_patient_details, potential_patient_id = get_patient_details_by_name(name)
                    if potential_patient_details:
                        break

        if potential_patient_details:
            # Reset user context if a new patient is detected
            if user_contexts[user_id]["patient_id"] is None or user_contexts[user_id]["patient_id"] != potential_patient_id:
                print("New patient detected in record. Resetting user context.")
                user_contexts[user_id] = {
                    "patient_id": potential_patient_id,
                    "patient_details": potential_patient_details,
                    "nurse_notes": get_patient_notes(potential_patient_id),
                    "chat_history": []  # Reset chat history as well
                }
            patient_id = potential_patient_id
            patient_details = potential_patient_details
        else:
            return jsonify({"error": "Patient room number or name required to save note."}), 400

    # Step 2: Update nurse notes in user context
    user_contexts[user_id]["nurse_notes"] = get_patient_notes(patient_id)

    # Add the new note to nurse notes in the context
    new_note = f"{current_date}: {patient_note}\n"
    if isinstance(user_contexts[user_id]["nurse_notes"], str):
        user_contexts[user_id]["nurse_notes"] += new_note
    else:
        user_contexts[user_id]["nurse_notes"] = new_note

    # Write the note to the nurse_notes.csv file
    new_note_csv = f"{current_date}, {user_id}, {patient_id}, {patient_note}\n"
    try:
        with open("nurse_notes.csv", 'a') as file:
            file.write(new_note_csv)
    except FileNotFoundError:
        with open("nurse_notes.csv", 'w') as file:
            file.write("Date, NurseID, PatientID, Note\n")
            file.write(new_note_csv)

    # Step 3: Update context for record chain
    context = global_context
    context += "\nChat History:\n" + "\n".join(
        [f"User: {entry['user']}\nAI: {entry['ai']}" for entry in user_contexts[user_id]["chat_history"]]
    )

    print("Calling record chain...")
    result = recordChain.invoke({
        "context": context,
        "patient_details": user_contexts[user_id]["patient_details"],
        "patient_note": patient_note
    })
    print("Record chain response received.")

    # Add the result to chat history
    user_contexts[user_id]["chat_history"].append({"user": patient_note, "ai": result})

    return jsonify({"response": result}), 200

@app.route('/set_global_context', methods=['POST'])
def set_global_context():
    global global_context
    data = request.json
    global_context = data.get("context")
    print("Global context updated.")
    return jsonify({"message": "Global context updated successfully"}), 200

@app.route('/test', methods=['GET'])
def test():
    return jsonify({"message": "Server is running!"}), 200

if __name__ == '__main__':
    # Start the Flask app
    app.run(debug=True, host="0.0.0.0", port=5000)
