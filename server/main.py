# Required imports
import os
import time
import pandas as pd
import threading
from concurrent.futures import ThreadPoolExecutor, CancelledError, Future # Import Future for type hinting
from flask import Flask, request, jsonify
from flask_cors import CORS
from langchain_ollama import OllamaLLM
from langchain_core.prompts import ChatPromptTemplate
import uuid # For generating unique request IDs
import datetime # For timestamps and cleanup logic
import traceback # For detailed error logging
import re # For regular expression matching (HETU)
from typing import Dict, Any, Optional, Tuple, List # For type hinting

# --- Configuration Constants ---
PATIENT_DATA_PATH = "patient_data.csv"
NURSE_NOTES_PATH = "nurse_notes.csv"
OLLAMA_MODEL_NAME = "irzumbm/pulseAITiny" # Specify the Ollama model to use
MAX_WORKERS = 5 # Max concurrent requests processed by the thread pool
CLEANUP_INTERVAL_SECONDS = 120 # How often the background cleanup runs (2 minutes)
STALE_REQUEST_THRESHOLD_SECONDS = 300 # How long completed/errored requests stay before cleanup (5 minutes)
PROCESSING_TIMEOUT_SECONDS = 600 # Optional: Timeout for requests stuck in 'processing' (10 minutes)
SSN_COLUMN_NAME = "SSN" # Column name for SSN/HETU in patient_data.csv
PATIENT_ID_COLUMN_NAME = "Patient ID" # Column name for internal Patient ID in patient_data.csv
PATIENT_NAME_COLUMN_NAME = "Patient Name" # Column name for Patient Name in patient_data.csv
ROOM_NUMBER_COLUMN_NAME = "Room Number" # Column name for Room Number in patient_data.csv
NOTES_PATIENT_ID_COLUMN = "PatientID" # Column name for Patient ID in nurse_notes.csv
NOTES_NURSE_ID_COLUMN = "NurseID" # Column name for Nurse ID in nurse_notes.csv
NOTES_DATE_COLUMN = "Date" # Column name for Date in nurse_notes.csv
NOTES_NOTE_COLUMN = "Note" # Column name for Note content in nurse_notes.csv

# --- Initialize Flask App ---
app = Flask(__name__)
CORS(app) # Enable CORS for all origins for easier development/testing

# --- Thread Safety Locks ---
# Lock for accessing/modifying the shared user_contexts dictionary
user_contexts_lock = threading.Lock()
# Lock for accessing/modifying the shared active_requests dictionary
active_requests_lock = threading.Lock()

# --- Model and Prompt Templates ---
# Template for general chat interactions, focusing on context and patient info
chatTemplate = """
Answer the question below, prioritizing information from the provided context. If you must provide information outside the context, explicitly state that it is not from the provided data. Do not fabricate information.
Current Time: {current_time}

Context: {context}

Patient Details: {patient_details}

Summarized Nurse Notes: {nurse_notes_result}

Question: {question}

Instructions:
* Be concise and clear in your answer. Avoid any details that might not be useful to your fellow nurse.
* Start by addressing any urgent alerts or recent updates (based on the provided timestamps).
* Do not repeat information already present in the context unless specifically asked to.
* Answer in a natural, human-like conversational style.
* Do not include dates or timestamps in your response, unless specifically requested.
* Do not use emojis.
* Focus on the most relevant information for the question.
* Always use patient's name when referring to them, and never their ID or SSN.

Answer:
"""

# Template for confirming a note recording action
recordingTemplate = """
Answer the Question Below. If you are providing patient details, only provide information that can be found in the context and if you are providing something outside the context, mention that clearly.

Here is the conversation history: {context}

Here are the patient details: {patient_details}

Here is the patient note that was just recorded: {patient_note}

Generate a short confirmation message to keep the flow of the conversation going naturally.

* Always use patient's name when referring to them, and never their ID or SSN.

Response:
"""

# Template for summarizing nurse notes by theme
nurseNotesTemplate = """
Answer the Question Below. If you are providing patient details, only provide information that can be found in the context and if you are providing something outside the context, mention that clearly.

Here are the raw nurse notes:
{nurse_notes}

Given the list of these notes, group the notes by common themes. For each theme, provide only the latest update based on the date associated with the note. Do not provide any reasoning or explanation for the grouping, just the themed summaries.
Response:
"""

# --- Model Initialization ---
model: Optional[OllamaLLM] = None
chatChain: Optional[Any] = None
recordChain: Optional[Any] = None
nurseNotesChain: Optional[Any] = None

try:
    model = OllamaLLM(model=OLLAMA_MODEL_NAME)
    # You could add other parameters here, e.g., temperature, max_tokens if needed
    # model = OllamaLLM(model=OLLAMA_MODEL_NAME, max_tokens=150)
    print(f"Ollama LLM initialized successfully with model: {OLLAMA_MODEL_NAME}")

    # Create LangChain chains using the initialized model and prompts
    chatPrompt = ChatPromptTemplate.from_template(chatTemplate)
    recordPrompt = ChatPromptTemplate.from_template(recordingTemplate)
    nurseNotesPrompt = ChatPromptTemplate.from_template(nurseNotesTemplate)
    chatChain = chatPrompt | model
    nurseNotesChain = nurseNotesPrompt | model
    recordChain = recordPrompt | model
    print("LangChain processing chains created.")

except Exception as llm_err:
    print(f"ERROR initializing Ollama LLM or creating chains: {llm_err}")
    print("Ensure Ollama server is running and the specified model is available.")
    print("Server will run, but /chat, /record, and note summarization will fail.")
    # model, chatChain, recordChain, nurseNotesChain remain None


# --- Context Storage ---
# Global context string (can be set via API)
global_context: str = ""
# Dictionary to store context per user {user_id: {"patient_id": ..., "patient_details": ..., "nurse_notes": ..., "chat_history": [...]}}
user_contexts: Dict[str, Dict[str, Any]] = {}

# --- Active Request Tracking ---
# Stores information about ongoing asynchronous requests
# Format: { request_id: {"future": Future, "status": str, "result": Optional[Any], "cancel_requested": bool, "user_id": str, "timestamp": datetime} }
# Status values: "processing", "cancelling", "completed", "error", "cancelled"
active_requests: Dict[str, Dict[str, Any]] = {}

# --- HETU (Finnish Personal Identity Code) Handling ---
# Regex to find HETU with a century separator (+, -, A)
# Format: DDMMYY<sep>NNNC where C is a checksum character
HETU_WITH_SEP_REGEX = re.compile(r'\b(\d{6}[-+A]\d{3}[0-9A-FHJ-NPR-Y])\b', re.IGNORECASE)
# Regex to find HETU without a separator (requires reconstruction)
# Captures date part (DDMMYY) and identifier part (NNNC) separately
HETU_WITHOUT_SEP_REGEX = re.compile(r'\b(\d{6})(\d{3}[0-9A-FHJ-NPR-Y])\b', re.IGNORECASE)

def reconstruct_hetu_with_separator(date_part: str, id_part: str) -> Optional[str]:
    """
    Reconstructs a full HETU with the correct century separator based on the year.
    Assumes YY <= current_year means 2000s ('A'), YY > current_year means 1900s ('-').
    Handles potential errors in date/ID parts.

    Args:
        date_part: The DDMMYY part of the HETU.
        id_part: The NNNC (number and checksum) part of the HETU.

    Returns:
        The reconstructed HETU string in uppercase (e.g., "DDMMYY-NNNC") or None if reconstruction fails.
    """
    if len(date_part) != 6 or len(id_part) != 4:
        print(f"Error: Invalid HETU parts for reconstruction: date='{date_part}', id='{id_part}'")
        return None
    try:
        # Simple century determination logic based on current year.
        # This might need adjustment for dates far in the past or future,
        # or edge cases around the turn of the year.
        current_year_short = datetime.datetime.now().year % 100
        year_yy = int(date_part[4:6])

        if 0 <= year_yy <= current_year_short: # Assume 2000s
            century_marker = 'A'
            # full_year = 2000 + year_yy # Optional: for validation
        elif current_year_short < year_yy <= 99: # Assume 1900s
            century_marker = '-'
            # full_year = 1900 + year_yy # Optional: for validation
        else:
             # Add logic for 1800s ('+') if needed, though less common now.
             # For simplicity, we'll treat very old years as potentially invalid here.
             print(f"Warning: HETU year '{year_yy}' from '{date_part}' falls outside expected 1900-20xx range.")
             # Decide how to handle: return None or assign a default marker?
             # Returning None is safer if the date logic isn't exhaustive.
             return None # Or handle 1800s with '+' if required

        # Optional: Basic date validation (e.g., check if day/month are valid)
        # try:
        #     datetime.datetime.strptime(f"{date_part[:4]}{full_year}", '%d%m%Y')
        # except ValueError:
        #     print(f"Warning: Reconstructed HETU date {date_part[:4]}{full_year} appears invalid.")
        #     # return None # Or proceed with potentially invalid date

        # Combine parts, ensuring canonical uppercase format
        return f"{date_part}{century_marker}{id_part[:-1]}{id_part[-1].upper()}"

    except ValueError:
        print(f"Error: Invalid year format in date_part '{date_part}' during HETU reconstruction.")
        return None # Invalid year format

# --- Patient Data Loading ---
patient_data: pd.DataFrame = pd.DataFrame() # Initialize as empty DataFrame
try:
    patient_data = pd.read_csv(PATIENT_DATA_PATH)
    # Attempt to convert key columns to string immediately to avoid later issues
    for col in [ROOM_NUMBER_COLUMN_NAME, SSN_COLUMN_NAME, PATIENT_ID_COLUMN_NAME, PATIENT_NAME_COLUMN_NAME]:
         if col in patient_data.columns:
              patient_data[col] = patient_data[col].astype(str)
         else:
              print(f"Warning: Expected column '{col}' not found in {PATIENT_DATA_PATH}. Patient lookups involving this column may fail.")
    print(f"Patient data loaded successfully from {PATIENT_DATA_PATH}")
except FileNotFoundError:
    print(f"Error: Patient data file not found at {PATIENT_DATA_PATH}. Patient lookups will not work.")
except Exception as e:
    print(f"Error reading or processing patient data from {PATIENT_DATA_PATH}: {e}")
    traceback.print_exc()
    # patient_data remains an empty DataFrame

# --- Thread Pool Executor ---
# Manages the worker threads for handling asynchronous requests (/chat, /record)
executor = ThreadPoolExecutor(max_workers=MAX_WORKERS)
print(f"ThreadPoolExecutor initialized with {MAX_WORKERS} workers.")


# --- Helper Functions ---

def patient_details_to_string(patient_dict: Optional[Dict[str, Any]]) -> str:
    """
    Converts a patient data dictionary into a simple string format for LLM context.
    Includes only primitive types (str, int, float, bool).

    Args:
        patient_dict: The dictionary containing patient data (potentially from pandas).

    Returns:
        A comma-separated string of key-value pairs, or a default message if input is None/empty.
    """
    if not patient_dict:
        return "No patient details available."
    # Filter for basic types easily representable as strings
    primitive_dict = {k: v for k, v in patient_dict.items() if isinstance(v, (str, int, float, bool))}
    return ", ".join(f"{key}: {value}" for key, value in primitive_dict.items())

def get_patient_details_by_room(room_number: str) -> Optional[Tuple[Dict[str, Any], str]]:
    """
    Finds a patient by matching the room number. Assumes ROOM_NUMBER_COLUMN_NAME exists and is string type.

    Args:
        room_number: The room number to search for (as a string).

    Returns:
        A tuple containing (patient_details_dict, patient_id) if found, otherwise None.
        Returns None if patient data is empty or columns are missing.
    """
    if patient_data.empty or ROOM_NUMBER_COLUMN_NAME not in patient_data.columns:
        print(f"Warning: Cannot search by room '{room_number}'. Patient data empty or missing '{ROOM_NUMBER_COLUMN_NAME}' column.")
        return None
    try:
        # Direct string comparison since column was pre-converted
        patient_info = patient_data[patient_data[ROOM_NUMBER_COLUMN_NAME] == room_number]
        if not patient_info.empty:
            patient = patient_info.iloc[0].to_dict()
            patient_id = patient.get(PATIENT_ID_COLUMN_NAME)
            if not patient_id:
                 print(f"Warning: Found patient in room {room_number} but missing '{PATIENT_ID_COLUMN_NAME}'.")
                 # Decide if you want to return the patient dict anyway, or None
                 # return patient, None # Option 1: Return details without ID
                 return None # Option 2: Consider it a failure if ID is missing
            print(f"Patient detected by room: {room_number}, Name: {patient.get(PATIENT_NAME_COLUMN_NAME)}, ID: {patient_id}")
            return patient, str(patient_id) # Ensure ID is string
    except Exception as e:
        print(f"Error getting patient by room {room_number}: {e}")
        traceback.print_exc()
    return None

def get_patient_details_by_name(patient_name: str) -> Optional[Tuple[Dict[str, Any], str]]:
    """
    Finds a patient by partially matching the name (case-insensitive). Assumes PATIENT_NAME_COLUMN_NAME exists.

    Args:
        patient_name: The name (or part of it) to search for.

    Returns:
        A tuple containing (patient_details_dict, patient_id) if found, otherwise None.
        Returns None if patient data is empty or columns are missing.
    """
    if patient_data.empty or PATIENT_NAME_COLUMN_NAME not in patient_data.columns:
        print(f"Warning: Cannot search by name '{patient_name}'. Patient data empty or missing '{PATIENT_NAME_COLUMN_NAME}' column.")
        return None
    try:
        # Case-insensitive containment search, handling potential NaN values
        patient_info = patient_data[patient_data[PATIENT_NAME_COLUMN_NAME].str.contains(patient_name, case=False, na=False)]
        if not patient_info.empty:
            if len(patient_info) > 1:
                print(f"Warning: Multiple patients found for name '{patient_name}'. Using the first match.")
            patient = patient_info.iloc[0].to_dict()
            patient_id = patient.get(PATIENT_ID_COLUMN_NAME)
            if not patient_id:
                 print(f"Warning: Found patient matching name '{patient_name}' but missing '{PATIENT_ID_COLUMN_NAME}'.")
                 return None # Require Patient ID for a valid match
            print(f"Patient detected by name: {patient_name}, ID: {patient_id}")
            return patient, str(patient_id) # Ensure ID is string
    except Exception as e:
        print(f"Error getting patient by name '{patient_name}': {e}")
        traceback.print_exc()
    return None

def get_patient_details_by_ssn(ssn: str) -> Optional[Tuple[Dict[str, Any], str]]:
    """
    Retrieves patient details based on an exact SSN/HETU match.
    Uses the SSN_COLUMN_NAME constant. Compares using uppercase canonical HETU format.

    Args:
        ssn: The SSN/HETU string to search for (should be in canonical uppercase format).

    Returns:
        A tuple containing (patient_details_dict, patient_id) if found, otherwise None.
        Returns None if patient data is empty or columns are missing.
    """
    if patient_data.empty or SSN_COLUMN_NAME not in patient_data.columns:
        print(f"Warning: Cannot search by SSN '{ssn}'. Patient data empty or missing '{SSN_COLUMN_NAME}' column.")
        return None

    # Ensure the search SSN is uppercase (though input should already be canonical)
    search_ssn_upper = str(ssn).upper()

    try:
        # Direct comparison since column was pre-converted to string and search term is uppercase
        patient_info = patient_data[patient_data[SSN_COLUMN_NAME].str.upper() == search_ssn_upper]

        if not patient_info.empty:
            if len(patient_info) > 1:
                print(f"Warning: Multiple patients found for SSN {ssn}. Using the first match.")
            patient = patient_info.iloc[0].to_dict()
            patient_id = patient.get(PATIENT_ID_COLUMN_NAME)
            if not patient_id:
                 print(f"Warning: Found patient with SSN '{ssn}' but missing '{PATIENT_ID_COLUMN_NAME}'.")
                 return None # Require Patient ID
            print(f"Patient detected by SSN: {ssn}, Name: {patient.get(PATIENT_NAME_COLUMN_NAME)}, ID: {patient_id}")
            # Ensure the SSN returned in the dict matches the one found (it should if lookup worked)
            # patient[SSN_COLUMN_NAME] = search_ssn_upper # Can enforce canonical form if needed
            return patient, str(patient_id) # Ensure ID is string
        else:
            # This case should ideally be handled by the caller checking the return value.
            # Logging here is okay for debugging.
            # print(f"SSN '{search_ssn_upper}' not found in patient records.")
            pass
    except Exception as e:
        print(f"Error getting patient by SSN {ssn}: {e}")
        traceback.print_exc()
    return None

def _identify_patient(text_to_search: str, request_id: str) -> Optional[Tuple[Dict[str, Any], str]]:
    """
    Attempts to identify a patient within the given text using Room Number, Name, or SSN/HETU.
    Prioritizes Room > Name > SSN (with separator) > SSN (without separator).

    Args:
        text_to_search: The input string (e.g., user message, note content) to scan.
        request_id: The ID of the current request for logging purposes.

    Returns:
        A tuple (patient_details_dict, patient_id) if a patient is uniquely identified,
        otherwise None.
    """
    if patient_data.empty:
        print(f"[{request_id}] Patient identification skipped: Patient data is empty.")
        return None

    # 1. Check by Room Number
    if ROOM_NUMBER_COLUMN_NAME in patient_data.columns:
        # Iterate through unique room numbers present in the data
        for room_number_str in patient_data[ROOM_NUMBER_COLUMN_NAME].dropna().unique():
            # Check for "room X" or just "X" (adjust pattern if needed)
            if f"room {room_number_str}" in text_to_search.lower() or \
               f"huone {room_number_str}" in text_to_search.lower() or \
               room_number_str == text_to_search.strip():
                result = get_patient_details_by_room(room_number_str)
                if result:
                    print(f"[{request_id}] Patient identified in text by Room: {room_number_str}")
                    return result

    # 2. Check by Name (if not found by room)
    if PATIENT_NAME_COLUMN_NAME in patient_data.columns:
        # Iterate through unique names (more robust might be needed for partial matches)
        for name in patient_data[PATIENT_NAME_COLUMN_NAME].dropna().unique():
             # Simple substring check (case-insensitive). Might need refinement (e.g., word boundaries)
             if name.lower() in text_to_search.lower():
                 result = get_patient_details_by_name(name)
                 if result:
                     print(f"[{request_id}] Patient identified in text by Name: {name}")
                     return result # Return first name match

    # 3. Check by SSN/HETU (if not found by room or name)
    if SSN_COLUMN_NAME in patient_data.columns:
        # 3a. Try matching HETU WITH separator first
        potential_ssns_with_sep = HETU_WITH_SEP_REGEX.findall(text_to_search)
        if potential_ssns_with_sep:
            print(f"[{request_id}] Potential HETU (with sep) found in text: {potential_ssns_with_sep}")
            for potential_ssn in potential_ssns_with_sep:
                # Uppercase for consistent lookup
                found_hetu_canonical = potential_ssn.upper()
                result = get_patient_details_by_ssn(found_hetu_canonical)
                if result:
                    print(f"[{request_id}] Patient identified in text by SSN (with sep): {found_hetu_canonical}")
                    return result # Stop after first valid SSN match

        # 3b. If not found, try matching HETU WITHOUT separator
        potential_ssns_without_sep = HETU_WITHOUT_SEP_REGEX.findall(text_to_search)
        if potential_ssns_without_sep:
            print(f"[{request_id}] Potential HETU (without sep) found in text: {potential_ssns_without_sep}")
            for date_part, id_part in potential_ssns_without_sep:
                # Reconstruct the canonical form with separator
                reconstructed_hetu = reconstruct_hetu_with_separator(date_part, id_part)
                if reconstructed_hetu:
                    found_hetu_canonical = reconstructed_hetu # Already uppercased by helper
                    print(f"[{request_id}] Reconstructed HETU: {found_hetu_canonical} from text fragment {date_part}{id_part}")
                    result = get_patient_details_by_ssn(found_hetu_canonical)
                    if result:
                        print(f"[{request_id}] Patient identified in text by SSN (reconstructed): {found_hetu_canonical}")
                        return result # Stop after first valid SSN match
                else:
                    print(f"[{request_id}] Could not reconstruct valid HETU from {date_part}{id_part}")

    # If no patient identified by any method
    print(f"[{request_id}] No patient identifier found or matched in the provided text.")
    return None

def get_patient_notes(patient_id: str, request_id: str) -> Dict[str, Any]:
    """
    Retrieves raw patient notes from the CSV file, filters them for the given patient ID,
    and then uses the nurseNotesChain (LLM) to summarize them by theme.

    Args:
        patient_id: The ID of the patient whose notes are needed.
        request_id: The ID of the current request for logging and cancellation checks.

    Returns:
        A dictionary containing the summarized notes under the key "summary",
        or an error message under the key "error".
    """
    if not patient_id:
        return {"error": "No patient ID provided for notes lookup."}

    # --- 1. Read and Filter Notes ---
    try:
        nurse_notes_df = pd.read_csv(NURSE_NOTES_PATH)
    except FileNotFoundError:
        return {"error": f"Notes file not found at {NURSE_NOTES_PATH}."}
    except Exception as e:
        print(f"[{request_id}] Error reading {NURSE_NOTES_PATH}: {e}")
        return {"error": f"Error reading nurse notes file: {e}"}

    if NOTES_PATIENT_ID_COLUMN not in nurse_notes_df.columns:
        return {"error": f"Required column '{NOTES_PATIENT_ID_COLUMN}' not found in notes file."}

    try:
        # Ensure comparison is done using string types
        notes_df = nurse_notes_df[nurse_notes_df[NOTES_PATIENT_ID_COLUMN].astype(str) == str(patient_id)]
    except Exception as e:
        print(f"[{request_id}] Error filtering notes for patient ID {patient_id}: {e}")
        return {"error": f"Error filtering notes: {e}"}

    if notes_df.empty:
        return {"summary": "No notes available for this patient."} # Return summary indicating no notes

    # --- 2. Format Notes for LLM ---
    notes_list: List[Dict[str, str]] = []
    for _, row in notes_df.iterrows():
        try:
            note_dict = row.to_dict()
            # Use defined column constants and provide defaults
            date_str = str(note_dict.get(NOTES_DATE_COLUMN, 'N/A'))
            note_str = str(note_dict.get(NOTES_NOTE_COLUMN, ''))
            if note_str: # Only include rows with actual note content
                 notes_list.append({"date": date_str, "note": note_str})
        except Exception as conv_e:
            print(f"[{request_id}] Error converting note row to dict for patient {patient_id}: {conv_e}")
            # Continue processing other notes

    if not notes_list:
        return {"summary": "No valid notes found for processing after filtering."}

    # --- 3. Cancellation Check Before LLM Call ---
    with active_requests_lock:
        request_info = active_requests.get(request_id)
        if request_info and request_info.get("cancel_requested", False):
            print(f"[{request_id}] Cancellation detected before nurse notes summarization.")
            raise CancelledError(f"Request {request_id} cancelled before nurse note AI.")

    # --- 4. Summarize Notes using LLM ---
    if not nurseNotesChain:
        print(f"[{request_id}] Error: NurseNotesChain (LLM) is not available.")
        return {"error": "Notes processing service unavailable."}

    try:
        print(f"[{request_id}] Processing {len(notes_list)} nurse notes using AI for summarization...")
        nurse_start_time = time.time()
        # Pass the list of dictionaries directly to the template
        nurse_notes_result = nurseNotesChain.invoke({"nurse_notes": notes_list})
        nurse_end_time = time.time()
        print(f"[{request_id}] Nurse Notes AI Summarization time: {nurse_end_time - nurse_start_time:.2f} seconds")

        # Ensure result is a string or simple structure; wrap in dict
        if isinstance(nurse_notes_result, (str, dict, list)):
             return {"summary": nurse_notes_result}
        else:
             # Attempt to convert unexpected result type to string
             print(f"[{request_id}] Warning: Unexpected type from nurseNotesChain: {type(nurse_notes_result)}. Converting to string.")
             return {"summary": str(nurse_notes_result)}

    except CancelledError:
        # Propagate cancellation upwards
        raise
    except Exception as e:
        print(f"[{request_id}] Error processing nurse notes with AI: {e}")
        traceback.print_exc()
        return {"error": f"Error summarizing nurse notes: {e}"}


# --- Core Request Processing Functions ---

def process_chat(data: Dict[str, Any], request_id: str, handsfree: bool) -> Dict[str, Any]:
    """
    Handles a chat request: identifies patient context, retrieves relevant info,
    calls the LLM, updates history, and formats the response (optionally for handsfree).

    Args:
        data: Dictionary containing request data ("user_id", "message", "language").
        request_id: Unique identifier for this request.
        handsfree: Boolean indicating if the handsfree prefix should be added.

    Returns:
        A dictionary containing the response data ("response", "patient_name", "SSN").
        If an error occurs internally that should be reported to the user (e.g., LLM unavailable),
        it might be returned within this dictionary under an "error" key, though typically
        errors are caught and set in the active_requests structure.
        Raises CancelledError if the task is cancelled.
    """
    start_time = time.time()
    user_id = data.get("user_id")
    user_input = data.get("message")
    user_language = data.get("language", "en") # Default to English if not provided

    print(f"[{request_id}] Request Starting (Chat - handsfree={handsfree}) User: {user_id}")

    # --- Input Validation ---
    if not user_id or not user_input:
        # This error should ideally be caught before submitting to executor,
        # but double-check here.
        print(f"[{request_id}] Error: Missing user_id or message.")
        # Return an error structure compatible with _handle_future_completion
        return {"error": "Missing user_id or message in chat request"}
    if not chatChain:
        print(f"[{request_id}] Error: Chat service unavailable (LLM not initialized).")
        return {"error": "Chat service unavailable."}

    try:
        # --- Initialize Context for this Request ---
        current_global_context = global_context + f"\nRespond in {'Finnish' if user_language == 'fi' else 'English'}."
        patient_details_in_context: Optional[Dict[str, Any]] = None
        patient_id_in_context: Optional[str] = None
        summarized_notes: Any = {"summary": "No patient context set or notes unavailable."} # Default notes state
        chat_history_list: List[Dict[str, str]] = []

        # --- Lock User Context for Reading/Updating ---
        with user_contexts_lock:
            # Ensure user context entry exists
            if user_id not in user_contexts:
                user_contexts[user_id] = {
                    "patient_id": None, "patient_details": None,
                    "summarized_notes": None, "chat_history": []
                }
            user_context_data = user_contexts[user_id]
            patient_id_in_context = user_context_data.get("patient_id")
            patient_details_in_context = user_context_data.get("patient_details")
            chat_history_list = user_context_data.get("chat_history", []) # Load current history

        # --- Identify Patient in User Input ---
        # Use the helper function to check the current user message
        identified_patient_info = _identify_patient(user_input, request_id)
        new_patient_details: Optional[Dict[str, Any]] = None
        new_patient_id: Optional[str] = None

        if identified_patient_info:
            new_patient_details, new_patient_id = identified_patient_info

            # --- Update User Context if New Patient Identified ---
            if new_patient_id and new_patient_id != patient_id_in_context:
                print(f"[{request_id}] New patient context detected in input for user {user_id}: ID {new_patient_id}. Resetting context.")
                with user_contexts_lock:
                    # Fetch notes for the *new* patient (this involves an LLM call)
                    summarized_notes = get_patient_notes(new_patient_id, request_id)
                    # Check for cancellation *after* potential notes call
                    request_info_check = active_requests.get(request_id)
                    if request_info_check and request_info_check.get("cancel_requested", False):
                         raise CancelledError(f"Request {request_id} cancelled during patient context switch.")

                    user_contexts[user_id] = {
                        "patient_id": new_patient_id,
                        "patient_details": new_patient_details,
                        "summarized_notes": summarized_notes, # Store the fetched/summarized notes
                        "chat_history": [] # Reset history for new patient
                    }
                    # Update local variables for the rest of this request
                    patient_id_in_context = new_patient_id
                    patient_details_in_context = new_patient_details
                    chat_history_list = []
            elif new_patient_id and new_patient_id == patient_id_in_context:
                 print(f"[{request_id}] Patient mentioned in input ({new_patient_id}) matches current context.")
                 # Patient context is already correct. Ensure notes are loaded if missing.
                 with user_contexts_lock:
                      if not user_contexts[user_id].get("summarized_notes") and patient_id_in_context:
                           print(f"[{request_id}] Notes were missing for current patient {patient_id_in_context}, fetching now.")
                           summarized_notes = get_patient_notes(patient_id_in_context, request_id)
                           # Check for cancellation again
                           request_info_check = active_requests.get(request_id)
                           if request_info_check and request_info_check.get("cancel_requested", False):
                               raise CancelledError(f"Request {request_id} cancelled while fetching missing notes.")
                           user_contexts[user_id]["summarized_notes"] = summarized_notes
                      else:
                           # Notes already loaded, use them
                           summarized_notes = user_contexts[user_id].get("summarized_notes", summarized_notes) # Use existing or default

        else:
            # No patient identified in the *current* input. Use existing context.
            print(f"[{request_id}] No new patient identified in input. Using existing context (Patient ID: {patient_id_in_context}).")
            # Ensure notes are loaded if context exists but notes are missing
            with user_contexts_lock:
                 if patient_id_in_context and not user_contexts[user_id].get("summarized_notes"):
                      print(f"[{request_id}] Notes were missing for current patient {patient_id_in_context}, fetching now.")
                      summarized_notes = get_patient_notes(patient_id_in_context, request_id)
                      request_info_check = active_requests.get(request_id)
                      if request_info_check and request_info_check.get("cancel_requested", False):
                            raise CancelledError(f"Request {request_id} cancelled while fetching missing notes.")
                      user_contexts[user_id]["summarized_notes"] = summarized_notes
                 elif patient_id_in_context:
                      # Notes exist, use them
                      summarized_notes = user_contexts[user_id].get("summarized_notes", summarized_notes)
                 # else: No patient context, notes remain default

        # --- Prepare Final Context for LLM ---
        # Convert current patient details (which might be None) to string
        current_patient_details_str = patient_details_to_string(patient_details_in_context)
        # Format chat history
        context_history_str = "\nChat History:\n" + "\n".join(
            [f"User: {entry['user']}\nAI: {entry['ai']}" for entry in chat_history_list[-10:]] # Limit history length
        )
        full_context_for_llm = current_global_context + context_history_str

        # --- Cancellation Check before Main LLM Call ---
        with active_requests_lock:
            request_info = active_requests.get(request_id)
            if not request_info or request_info.get("cancel_requested", False):
                print(f"[{request_id}] Cancellation detected before chat AI call.")
                raise CancelledError(f"Request {request_id} cancelled before chat AI.")

        # --- Invoke Chat LLM ---
        print(f"[{request_id}] Calling chat chain...")
        ai_start_time = time.time()
        current_time_str = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")

        chat_result = chatChain.invoke({
            "current_time": current_time_str,
            "context": full_context_for_llm,
            "question": user_input,
            "patient_details": current_patient_details_str,
            "nurse_notes_result": summarized_notes.get("summary", "Notes processing error or not available.") if isinstance(summarized_notes, dict) else str(summarized_notes) # Pass summary or error message
        })
        ai_end_time = time.time()
        print(f"[{request_id}] Chat chain response received ({ai_end_time - ai_start_time:.2f}s).")

        # --- Update Chat History ---
        with user_contexts_lock:
            # Check user_id exists again in case of rare edge cases
            if user_id in user_contexts:
                # Ensure 'chat_history' key exists and is a list
                if not isinstance(user_contexts[user_id].get("chat_history"), list):
                     user_contexts[user_id]["chat_history"] = []
                user_contexts[user_id]["chat_history"].append({"user": user_input, "ai": chat_result})
            else:
                # This shouldn't happen if context was created earlier, but log if it does
                print(f"[{request_id}] Warning: User context for {user_id} disappeared before saving chat history.")

        # --- Prepare Final Response ---
        final_response_string = str(chat_result) # Ensure it's a string

        # --- Handsfree Prefix Modification ---
        if handsfree:
            prefix = ""
            patient_name_hf = None
            ssn_hf = None

            # Use patient_details_in_context which reflects the active patient for this response
            if patient_details_in_context:
                patient_name_hf = patient_details_in_context.get(PATIENT_NAME_COLUMN_NAME)
                ssn_hf = patient_details_in_context.get(SSN_COLUMN_NAME)

                if patient_name_hf and ssn_hf:
                    ssn_str = str(ssn_hf)
                    # Check length for safety, HETU is longer but standard SSN might be shorter
                    if len(ssn_str) >= 4:
                        ssn_last_four = ssn_str[-4:]
                        prefix = f"For {patient_name_hf} with SSN ID ending in {ssn_last_four}, "
                        print(f"[{request_id}] Applying handsfree prefix.")
                        final_response_string = prefix + final_response_string
                    else:
                        print(f"[{request_id}] Handsfree active, but SSN '{ssn_str}' too short for prefix.")
                else:
                    print(f"[{request_id}] Handsfree active, but missing patient name or SSN in context for prefix.")
            else:
                 print(f"[{request_id}] Handsfree active, but no patient context available for prefix.")

        # --- Structure the Successful Result ---
        response_data = {
            "response": final_response_string,
            "patient_name": patient_details_in_context.get(PATIENT_NAME_COLUMN_NAME) if patient_details_in_context else None,
            "SSN": patient_details_in_context.get(SSN_COLUMN_NAME) if patient_details_in_context else None
        }
        end_time = time.time()
        print(f"[{request_id}] Total Chat Request time: {end_time - start_time:.2f} seconds. Ending Successfully.")
        return response_data

    except CancelledError as ce:
        print(f"[{request_id}] Chat Task explicitly cancelled: {ce}")
        raise # Propagate cancellation to be handled by _handle_future_completion
    except Exception as e:
        print(f"[{request_id}] Unhandled error during chat processing: {e}")
        traceback.print_exc()
        # Return an error structure that _handle_future_completion can use
        return {"error": f"An unexpected error occurred: {e}"}


def process_record(data: Dict[str, Any], request_id: str, handsfree: bool) -> Dict[str, Any]:
    """
    Handles a record request: identifies patient context (required), saves the note,
    calls the LLM for confirmation, updates history, and formats the response.

    Args:
        data: Dictionary containing request data ("user_id", "message").
        request_id: Unique identifier for this request.
        handsfree: Boolean indicating if the handsfree prefix should be added.

    Returns:
        A dictionary containing the response data ("response", "patient_name", "SSN")
        or an error message under the key "error".
        Raises CancelledError if the task is cancelled.
    """
    start_time = time.time()
    user_id = data.get("user_id")
    patient_note = data.get("message")
    current_date_str = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")

    print(f"[{request_id}] Request Starting (Record - handsfree={handsfree}) User: {user_id}")

    # --- Input Validation ---
    if not user_id or not patient_note:
        print(f"[{request_id}] Error: Missing user_id or message (note content).")
        return {"error": "Missing user_id or message in record request"}
    if not recordChain:
        print(f"[{request_id}] Error: Record service unavailable (LLM not initialized).")
        return {"error": "Record confirmation service unavailable."}

    try:
        # --- Determine Patient Context (Required for Recording) ---
        patient_id: Optional[str] = None
        patient_details: Optional[Dict[str, Any]] = None
        chat_history_list: List[Dict[str, str]] = [] # For confirmation context

        # 1. Check existing user context first
        with user_contexts_lock:
            if user_id in user_contexts and user_contexts[user_id].get("patient_id"):
                user_context_data = user_contexts[user_id]
                patient_id = user_context_data["patient_id"]
                patient_details = user_context_data.get("patient_details")
                chat_history_list = user_context_data.get("chat_history", [])
                print(f"[{request_id}] Patient ID {patient_id} found in existing context for record.")
            else:
                # 2. If no context, try identifying patient from the note content itself
                print(f"[{request_id}] No patient in context for record, searching note content...")
                identified_patient_info = _identify_patient(patient_note, request_id)
                if identified_patient_info:
                    patient_details, patient_id = identified_patient_info
                    print(f"[{request_id}] Patient ({patient_id}) identified in record note. Setting context.")
                    # Update context immediately (reset history and fetch notes if needed later)
                    # For recording, we mainly need the ID/details now. Notes aren't directly used by recordChain.
                    user_contexts[user_id] = {
                         "patient_id": patient_id,
                         "patient_details": patient_details,
                         "summarized_notes": None, # Mark notes as not yet fetched for this context
                         "chat_history": [] # Reset history as patient context was just established
                    }
                    chat_history_list = [] # Reset local history variable
                else:
                    # If no patient in context AND none found in note, this is an error
                    print(f"[{request_id}] Error: Patient context not set and no patient identifiable in note.")
                    return {"error": "Could not determine patient context. Please specify patient (e.g., 'Record note for room 101') or set context first."}

        # Final check after potential identification
        if not patient_id or not patient_details:
            print(f"[{request_id}] Error: Failed to establish valid patient context for saving note.")
            # This state should ideally not be reached due to checks above, but safeguard anyway.
            return {"error": "Internal error: Patient context could not be finalized."}

        # --- Write Note to CSV ---
        # Basic CSV safety: replace newlines, wrap in quotes if comma or quote exists.
        safe_note = str(patient_note).replace('\n', ' ').replace('\r', '')
        if ',' in safe_note or '"' in safe_note:
            safe_note = f'"{safe_note.replace("\"", "\"\"")}"' # CSV standard: double internal quotes

        new_note_csv_line = f"{current_date_str},{user_id},{patient_id},{safe_note}\n"

        try:
            # Use 'a+' to create if not exists, append otherwise.
            # Consider a dedicated file lock (e.g., threading.Lock) if high concurrent writes are expected
            # file_write_lock = threading.Lock() # Example
            # with file_write_lock:
            with open(NURSE_NOTES_PATH, 'a+', encoding='utf-8') as file: # Specify encoding
                # Check if file is empty to write header
                file.seek(0, os.SEEK_END)
                if file.tell() == 0:
                    file.write(f"{NOTES_DATE_COLUMN},{NOTES_NURSE_ID_COLUMN},{NOTES_PATIENT_ID_COLUMN},{NOTES_NOTE_COLUMN}\n") # Use constants for header
                # Write the new note
                file.write(new_note_csv_line)
            print(f"[{request_id}] Note for patient {patient_id} saved to {NURSE_NOTES_PATH}")
        except IOError as e:
            print(f"[{request_id}] Error writing note to {NURSE_NOTES_PATH}: {e}")
            traceback.print_exc()
            return {"error": f"Failed to save nurse note: {e}"}
        except Exception as e: # Catch other potential errors
             print(f"[{request_id}] Unexpected error during note saving: {e}")
             traceback.print_exc()
             return {"error": f"An unexpected error occurred while saving the note: {e}"}

        # --- Prepare Context for Confirmation LLM ---
        # Use patient details determined earlier
        current_patient_details_str = patient_details_to_string(patient_details)
        # Format chat history for context (use potentially updated chat_history_list)
        context_history_str = "\nChat History:\n" + "\n".join(
            [f"User: {entry['user']}\nAI: {entry['ai']}" for entry in chat_history_list[-5:]] # Shorter history for confirmation
        )
        full_context_for_llm = global_context + context_history_str # Use global context base

        # --- Cancellation Check before Confirmation LLM Call ---
        with active_requests_lock:
            request_info = active_requests.get(request_id)
            if not request_info or request_info.get("cancel_requested", False):
                print(f"[{request_id}] Cancellation detected before record confirmation AI call.")
                raise CancelledError(f"Request {request_id} cancelled before record confirmation AI.")

        # --- Invoke Confirmation LLM ---
        print(f"[{request_id}] Calling record confirmation chain...")
        ai_start_time = time.time()
        record_result = recordChain.invoke({
            "context": full_context_for_llm,
            "patient_details": current_patient_details_str,
            "patient_note": patient_note # Pass the original note for context
        })
        ai_end_time = time.time()
        print(f"[{request_id}] Record confirmation chain response received ({ai_end_time - ai_start_time:.2f}s).")

        # --- Update Chat History with Record Action & Confirmation ---
        with user_contexts_lock:
            if user_id in user_contexts:
                 if not isinstance(user_contexts[user_id].get("chat_history"), list):
                      user_contexts[user_id]["chat_history"] = []
                 # Add a combined entry showing the action and the AI's confirmation
                 user_contexts[user_id]["chat_history"].append({"user": f"Note Recorded: '{patient_note[:50]}...'", "ai": record_result}) # Log truncated note
            else:
                 print(f"[{request_id}] Warning: User context for {user_id} disappeared before saving record history.")


        # --- Prepare Final Response ---
        final_response_string = str(record_result) # Confirmation message from LLM
        final_response_string = "The following note was recorded:\n" + patient_note + ". " + final_response_string # Prepend confirmation message

        # --- Handsfree Prefix Modification ---
        if handsfree:
            prefix = ""
            # Use patient_details established earlier in this function
            if patient_details:
                patient_name_hf = patient_details.get(PATIENT_NAME_COLUMN_NAME)
                ssn_hf = patient_details.get(SSN_COLUMN_NAME)

                if patient_name_hf and ssn_hf:
                    ssn_str = str(ssn_hf)
                    if len(ssn_str) >= 4:
                        ssn_last_four = ssn_str[-4:]
                        prefix = f"For {patient_name_hf} with SSN ID ending in {ssn_last_four}, "
                        print(f"[{request_id}] Applying handsfree prefix to record confirmation.")
                        final_response_string = prefix + final_response_string
                    else:
                        print(f"[{request_id}] Handsfree active, but SSN '{ssn_str}' too short for record prefix.")
                else:
                    print(f"[{request_id}] Handsfree active, but missing patient name or SSN for record prefix.")
            else:
                 # This case should not happen due to earlier checks, but log if it does
                 print(f"[{request_id}] Handsfree active, but patient details were unexpectedly missing for prefix.")

        # --- Structure the Successful Result ---
        response_data = {
            "response": final_response_string, # Use the potentially modified string
            "patient_name": patient_details.get(PATIENT_NAME_COLUMN_NAME) if patient_details else None,
            "SSN": patient_details.get(SSN_COLUMN_NAME) if patient_details else None
        }
        end_time = time.time()
        print(f"[{request_id}] Total Record Request time: {end_time - start_time:.2f} seconds. Ending Successfully.")
        return response_data

    except CancelledError as ce:
        print(f"[{request_id}] Record Task explicitly cancelled: {ce}")
        raise # Propagate cancellation
    except Exception as e:
        print(f"[{request_id}] Unhandled error during record processing: {e}")
        traceback.print_exc()
        return {"error": f"An unexpected error occurred: {e}"}


# --- Asynchronous Task Completion Handler ---
def _handle_future_completion(request_id: str, future: Future):
    """
    Callback function executed when a background task (Future) completes, fails, or is cancelled.
    Updates the status and result in the active_requests dictionary.

    Args:
        request_id: The ID of the request associated with the future.
        future: The concurrent.futures.Future object representing the completed task.
    """
    with active_requests_lock:
        if request_id not in active_requests:
            print(f"[{request_id}] Completion callback triggered, but request ID no longer in active_requests (likely already cleaned up).")
            return

        request_info = active_requests[request_id]
        now = datetime.datetime.now() # Record completion time

        # Avoid double-processing if status was already finalized (e.g., by /cancel)
        if request_info["status"] not in ["processing", "cancelling"]:
            print(f"[{request_id}] Completion callback: Status already '{request_info['status']}'. Ignoring.")
            # Update timestamp even if status was already final? Maybe useful.
            # request_info["timestamp"] = now
            return

        try:
            if future.cancelled() or request_info["cancel_requested"]:
                # If future.cancel() succeeded or cancel was requested and task checked internally
                request_info["status"] = "cancelled"
                request_info["result"] = "Request was cancelled."
                print(f"[{request_id}] Task marked as cancelled.")
            else:
                # Task finished without explicit cancellation, get result or exception
                result_or_error = future.result() # This re-raises exceptions from the task function

                # Check if the task function returned an error structure (e.g., {'error': '...'})
                if isinstance(result_or_error, dict) and "error" in result_or_error:
                     request_info["status"] = "error"
                     request_info["result"] = result_or_error["error"] # Store just the error message
                     print(f"[{request_id}] Task completed with controlled error: {result_or_error['error']}")
                else:
                     # Assume successful completion, store the result dictionary
                     request_info["status"] = "completed"
                     request_info["result"] = result_or_error
                     print(f"[{request_id}] Task completed successfully.")

        except CancelledError:
            # Catch if future.result() raises CancelledError (should be covered by future.cancelled() check ideally)
            request_info["status"] = "cancelled"
            request_info["result"] = "Request was cancelled before completion."
            print(f"[{request_id}] Task cancellation confirmed by CancelledError exception.")
        except Exception as e:
            # Catch unexpected exceptions raised by future.result() (meaning the task function failed unexpectedly)
            print(f"[{request_id}] Completion callback: Task function raised unhandled exception: {e}")
            traceback.print_exc()
            request_info["status"] = "error"
            request_info["result"] = f"Internal server error during task execution: {str(e) or type(e).__name__}"

        # Always update the timestamp when the final state is determined
        request_info["timestamp"] = now
        print(f"[{request_id}] Final state set to '{request_info['status']}' by completion handler.")


# --- API Endpoints ---

@app.route('/chat', methods=['POST'])
def chat_submit():
    """
    API endpoint to submit a chat message.
    Starts an asynchronous task to process the chat.
    Returns: 202 Accepted with a request_id.
    """
    request_id = str(uuid.uuid4())
    try:
        data = request.json
        if not data: raise ValueError("Request body must be JSON.")
        user_id = data.get("user_id")
        handsfree_flag = data.get("handsfree", False) # Extract handsfree flag (default False)
        if not user_id: return jsonify({"error": "user_id is required"}), 400
        if not data.get("message"): return jsonify({"error": "message is required"}), 400

        print(f"[{request_id}] Received /chat request from user {user_id}, handsfree={handsfree_flag}")

        # Submit the processing function to the executor
        future = executor.submit(process_chat, data, request_id, handsfree_flag)

        # Store future and initial state
        with active_requests_lock:
            active_requests[request_id] = {
                "future": future, "status": "processing", "result": None,
                "cancel_requested": False, "user_id": user_id,
                "timestamp": datetime.datetime.now() # Record submission time
            }

        # Add the completion handler callback
        future.add_done_callback(lambda f: _handle_future_completion(request_id, f))

        return jsonify({"request_id": request_id}), 202 # Accepted

    except Exception as e:
        print(f"[chat_submit Error {request_id}] Failed to submit chat task: {e}")
        traceback.print_exc()
        return jsonify({"error": f"Failed to initiate chat request: {e}"}), 500


@app.route('/record', methods=['POST'])
def record_submit():
    """
    API endpoint to submit a nurse note for recording.
    Starts an asynchronous task to save the note and get confirmation.
    Returns: 202 Accepted with a request_id.
    """
    request_id = str(uuid.uuid4())
    try:
        data = request.json
        if not data: raise ValueError("Request body must be JSON.")
        user_id = data.get("user_id")
        handsfree_flag = data.get("handsfree", False) # Extract handsfree flag
        if not user_id: return jsonify({"error": "user_id is required"}), 400
        if not data.get("message"): return jsonify({"error": "message (note content) is required"}), 400

        print(f"[{request_id}] Received /record request from user {user_id}, handsfree={handsfree_flag}")

        # Submit the processing function to the executor
        future = executor.submit(process_record, data, request_id, handsfree_flag)

        # Store future and initial state
        with active_requests_lock:
             active_requests[request_id] = {
                 "future": future, "status": "processing", "result": None,
                 "cancel_requested": False, "user_id": user_id,
                 "timestamp": datetime.datetime.now()
             }

        # Add the completion handler callback
        future.add_done_callback(lambda f: _handle_future_completion(request_id, f))

        return jsonify({"request_id": request_id}), 202 # Accepted

    except Exception as e:
        print(f"[record_submit Error {request_id}] Failed to submit record task: {e}")
        traceback.print_exc()
        return jsonify({"error": f"Failed to initiate record request: {e}"}), 500


@app.route('/cancel/<request_id>', methods=['POST'])
def cancel_request(request_id: str):
    """
    API endpoint to request cancellation of an ongoing task.
    Attempts to cancel the future and updates the status.
    Returns: Status message and appropriate HTTP code.
    """
    print(f"[{request_id}] Received cancellation request.")
    status_code = 404
    message = "Request ID not found."
    was_processing = False

    with active_requests_lock:
        if request_id in active_requests:
            request_info = active_requests[request_id]
            current_status = request_info["status"]

            if current_status == "processing":
                was_processing = True
                request_info["cancel_requested"] = True # Mark intention immediately
                future = request_info["future"]

                if not future.done():
                    # Attempt to cancel the future.
                    # Returns True if cancellation is scheduled successfully (task might still run to completion if already started).
                    # Returns False if the task is already running and cannot be cancelled.
                    cancelled_attempt = future.cancel()
                    if cancelled_attempt:
                        # Future.cancel() suggests it might be interruptible or hasn't started.
                        # The callback (_handle_future_completion) will ultimately set the final 'cancelled' state.
                        # We mark it as 'cancelling' here to indicate the attempt.
                        request_info["status"] = "cancelling"
                        request_info["timestamp"] = datetime.datetime.now() # Update timestamp on state change
                        message = f"Cancellation requested. Attempting interruption (success={cancelled_attempt}). Final status pending."
                        status_code = 200
                        print(f"[{request_id}] Marked as 'cancelling', future.cancel() returned {cancelled_attempt}")
                    else:
                        # Task is running and cannot be interrupted by future.cancel().
                        # It will run to completion (success or error).
                        # The 'cancel_requested' flag allows the task itself to check and potentially exit early.
                        message = "Cancellation requested, but task could not be interrupted (already running). Task will complete or error out normally."
                        status_code = 200 # Request acknowledged, but cancellation not guaranteed immediately
                        print(f"[{request_id}] Marked cancel_requested=True, but future.cancel() returned {cancelled_attempt}")
                else: # Future was already done when cancel request arrived
                    message = f"Cancellation requested, but task already finished with final status '{current_status}'."
                    status_code = 400 # Bad request - can't cancel a finished task
                    print(f"[{request_id}] Cancel requested, but future already done (final status: {current_status}).")

            elif current_status == "cancelling":
                status_code = 200 # Already being cancelled
                message = "Cancellation already in progress."
            elif current_status in ["completed", "error", "cancelled"]:
                status_code = 400 # Bad request - can't cancel a terminal state
                message = f"Request already reached a final state ({current_status}). Cannot cancel."
            # else: # Should not happen if status values are managed correctly
            #     status_code = 500
            #     message = f"Request found in unexpected state: {current_status}"

        # else: request_id not in active_requests (initial message and code 404 apply)

    return jsonify({"message": message}), status_code


@app.route('/status/<request_id>', methods=['GET'])
def get_status(request_id: str):
    """
    API endpoint to poll for the status and result of a request.
    If the request is completed, errored, or cancelled, returns the result/error
    and implicitly cleans up the request entry.
    Returns: JSON with status, request_id, and optionally data or error.
    """
    print(f"[{request_id}] Received status request.")
    status_to_return = 404
    response_data = {"status": "not found", "request_id": request_id}
    entry_to_remove = None # Flag to remove the entry after sending response

    with active_requests_lock:
        if request_id in active_requests:
            request_info = active_requests[request_id]
            current_status = request_info["status"]
            print(f"[{request_id}] Current status in dict: {current_status}")

            if current_status == "completed":
                status_to_return = 200
                response_data = {"status": "completed", "request_id": request_id, "data": request_info.get("result")}
                entry_to_remove = request_id # Mark for cleanup
            elif current_status == "error":
                status_to_return = 200 # Report error successfully
                response_data = {"status": "error", "request_id": request_id, "error": request_info.get("result", "Unknown error")}
                entry_to_remove = request_id # Mark for cleanup
            elif current_status == "cancelled":
                status_to_return = 200
                response_data = {"status": "cancelled", "request_id": request_id, "message": request_info.get("result", "Request cancelled")}
                entry_to_remove = request_id # Mark for cleanup
            elif current_status == "cancelling":
                status_to_return = 200 # Still working on cancelling
                response_data = {"status": "cancelling", "request_id": request_id}
            elif current_status == "processing":
                status_to_return = 200 # Still processing
                response_data = {"status": "processing", "request_id": request_id}
            else: # Should not happen, indicates an internal state issue
                print(f"[{request_id}] Error: Request found in unknown state '{current_status}'.")
                status_to_return = 500
                response_data = {"status": "error", "error": f"Internal error: Unknown request state '{current_status}'", "request_id": request_id}
                entry_to_remove = request_id # Clean up inconsistent state

        # else: request_id not found (initial response_data and 404 code apply)

    # Perform cleanup if the request reached a terminal state and was retrieved
    if entry_to_remove:
        with active_requests_lock:
            if entry_to_remove in active_requests:
                final_status_reported = response_data.get('status', 'unknown')
                print(f"[{entry_to_remove}] Final state '{final_status_reported}' retrieved by client. Cleaning up request data.")
                del active_requests[entry_to_remove]
            # else: Entry might have been removed by background cleanup already - this is okay.

    return jsonify(response_data), status_to_return


# --- Background Cleanup Thread ---
def background_cleanup():
    """
    Periodically runs in a background thread to remove stale entries
    (completed, errored, cancelled) from the active_requests dictionary
    if they haven't been polled via /status recently.
    Optionally times out requests stuck in 'processing'.
    """
    print("Background cleanup thread started.")
    while True:
        try: # Wrap outer loop in try/except to prevent thread death on unexpected error
            time.sleep(CLEANUP_INTERVAL_SECONDS)
            now = datetime.datetime.now()
            cleaned_count = 0
            timed_out_count = 0

            with active_requests_lock:
                ids_to_remove = []
                # Iterate over a copy of keys to allow deletion during iteration
                current_request_ids = list(active_requests.keys())

                for req_id in current_request_ids:
                    # Safely get info in case it was deleted between list() and get() by /status
                    info = active_requests.get(req_id)
                    if not info: continue

                    timestamp = info.get("timestamp", now) # Use 'now' if timestamp is somehow missing

                    # Check terminal states for staleness
                    if info["status"] in ["completed", "error", "cancelled"]:
                        if (now - timestamp).total_seconds() > STALE_REQUEST_THRESHOLD_SECONDS:
                            ids_to_remove.append(req_id)
                            cleaned_count += 1

                    # Optional: Timeout for 'processing' or 'cancelling' states
                    elif PROCESSING_TIMEOUT_SECONDS > 0 and info["status"] in ["processing", "cancelling"]:
                        if (now - timestamp).total_seconds() > PROCESSING_TIMEOUT_SECONDS:
                             print(f"[Cleanup] Request {req_id} stuck in '{info['status']}' state for > {PROCESSING_TIMEOUT_SECONDS}s. Marking as timed out error.")
                             # Mark as error and schedule for removal (it will be removed on next cycle if not polled)
                             info["status"] = "error"
                             info["result"] = "Request timed out due to excessive processing time."
                             info["timestamp"] = now # Update timestamp to reflect error time
                             # Don't add to ids_to_remove immediately, let it age out as an error
                             timed_out_count += 1


                # Remove identified stale entries
                for req_id in ids_to_remove:
                    if req_id in active_requests:
                        print(f"[Cleanup] Removing stale request {req_id} (Status: {active_requests[req_id]['status']}, Age: {(now - active_requests[req_id]['timestamp']).total_seconds():.0f}s)")
                        del active_requests[req_id]
                    # else: already removed, possibly by /status endpoint

            if cleaned_count > 0: print(f"[Cleanup] Removed {cleaned_count} stale terminal requests.")
            if timed_out_count > 0: print(f"[Cleanup] Marked {timed_out_count} requests as timed out.")

        except Exception as cleanup_err:
            print(f"[Cleanup Thread Error] An error occurred during cleanup scan: {cleanup_err}")
            traceback.print_exc()
            # Avoid busy-looping if sleep fails, wait a bit before retrying
            time.sleep(60)


# --- Context Management API Endpoints ---

@app.route('/reset_user_context', methods=['POST'])
def reset_user_context():
    """
    API endpoint to clear the context (patient info, history) for a specific user.
    Also attempts to cancel any active requests for that user.
    """
    try:
        data = request.json
        if not data: raise ValueError("Request body must be JSON.")
        user_id = data.get("user_id")
        if not user_id: return jsonify({"error": "user_id is required"}), 400

        print(f"Received request to reset context for user {user_id}.")

        # --- Attempt to Cancel Active Requests for this User ---
        ids_to_cancel = []
        with active_requests_lock:
            # Find requests for this user that are still potentially running or cancelling
            for req_id, info in active_requests.items():
                if info.get("user_id") == user_id and info["status"] in ["processing", "cancelling"]:
                    ids_to_cancel.append(req_id)

        print(f"Found {len(ids_to_cancel)} active/cancelling requests for user {user_id} to attempt cancellation.")
        cancelled_count = 0
        for req_id in ids_to_cancel:
            print(f"Attempting cancellation for request {req_id} due to context reset.")
            # Use the existing /cancel logic by simulating a call or replicating logic
            # Replicating is safer to avoid HTTP overhead within the server
            with active_requests_lock:
                 if req_id in active_requests:
                     request_info = active_requests[req_id]
                     if request_info["status"] == "processing":
                         request_info["cancel_requested"] = True
                         future = request_info["future"]
                         if not future.done():
                             if future.cancel():
                                 request_info["status"] = "cancelling"
                                 request_info["timestamp"] = datetime.datetime.now()
                                 cancelled_count += 1
                                 print(f"Request {req_id} cancellation initiated.")
                             else:
                                 print(f"Request {req_id} could not be interrupted (already running). Marked for cancellation.")
                         else: print(f"Request {req_id} already done when attempting cancel for reset.")
                     elif request_info["status"] == "cancelling":
                         print(f"Request {req_id} already cancelling.")
                         request_info["cancel_requested"] = True # Ensure flag is set
                         cancelled_count += 1 # Count it as intended cancellation
                     # else: Already in terminal state, do nothing.
                 else: print(f"Request {req_id} disappeared before cancel attempt during reset.")


        # --- Reset the User Context Data ---
        user_existed = False
        with user_contexts_lock:
            if user_id in user_contexts:
                user_existed = True
                # Reset context fields to initial state
                user_contexts[user_id] = {
                    "patient_id": None,
                    "patient_details": None,
                    "summarized_notes": None,
                    "chat_history": []
                }
                print(f"User context reset successfully for user: {user_id}")
                msg = f"User context reset. {cancelled_count}/{len(ids_to_cancel)} active requests processed for cancellation."
            else:
                print(f"User context for {user_id} did not exist. No context data to reset.")
                msg = f"User context did not exist. {cancelled_count}/{len(ids_to_cancel)} active requests processed for cancellation."

        return jsonify({"message": msg, "user_existed": user_existed}), 200

    except Exception as e:
        print(f"[reset_user_context Error] Failed: {e}")
        traceback.print_exc()
        return jsonify({"error": f"Failed to reset user context: {e}"}), 500


@app.route('/set_global_context', methods=['POST'])
def set_global_context():
    """
    API endpoint to update the global context string used in LLM prompts.
    """
    global global_context
    try:
        data = request.json
        if not data: raise ValueError("Request body must be JSON.")
        new_context = data.get("context")
        if new_context is None: # Allow empty string but require key
            return jsonify({"error": "'context' key is required in JSON body"}), 400

        # Basic type check
        if not isinstance(new_context, str):
             return jsonify({"error": "'context' value must be a string"}), 400

        global_context = new_context
        print(f"Global context updated. New length: {len(global_context)} chars.")
        return jsonify({"message": "Global context updated successfully"}), 200
    except Exception as e:
         print(f"[set_global_context Error] Failed: {e}")
         traceback.print_exc()
         return jsonify({"error": f"Failed to set global context: {e}"}), 500

# --- Test Endpoint ---
@app.route('/test', methods=['GET'])
def test():
    """
    Simple endpoint to check if the server is running and responsive.
    """
    print("Test endpoint '/test' accessed.")
    return jsonify({"message": "Server is running!", "timestamp": datetime.datetime.now().isoformat()}), 200


if not app.debug or os.environ.get("WERKZEUG_RUN_MAIN") == "true":
    cleanup_thread = threading.Thread(target=background_cleanup, daemon=True)
    cleanup_thread.start()
    print("Background cleanup thread initiated.")

# # --- Main Execution ---
# if __name__ == '__main__':
#     # Start the background cleanup thread only when running the main script
#     if not app.debug or os.environ.get("WERKZEUG_RUN_MAIN") == "true":
#         cleanup_thread = threading.Thread(target=background_cleanup, daemon=True)
#         cleanup_thread.start()
#         print("Background cleanup thread initiated.")
#     else:
#          print("Skipping background thread start in Flask debug reload process.")

#     # Start the Flask development server
#     # Host '0.0.0.0' makes it accessible on the network
#     # Choose a port (e.g., 5001)
#     app.run(host='0.0.0.0', port=5000, debug=False) # Turn debug=False for production or when threads are critical