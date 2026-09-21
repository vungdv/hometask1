"""
Parallel / Multiple Tool Calls in a Single Turn Demonstration.
Using Google GenAI SDK and Gemini 3.6 Flash (gemini-3.6-flash).

This script demonstrates how Gemini can decide to execute MULTIPLE tools in parallel
during a single turn, and how an AI Agent handles, logs, executes, and returns
all tool responses bundled in the subsequent turn.
"""

import os
import sys
import json
import math
import getpass
from typing import Dict, Any, List
from google import genai
from google.genai import types

MODEL_ID = "gemini-3.6-flash"

# ==============================================================================
# 1. Tool Definitions
# ==============================================================================

def calculate_circle_area(radius: float) -> float:
    """Calculates the area of a circle given its radius.

    Args:
        radius: The radius of the circle in meters.
    """
    area = math.pi * (radius ** 2)
    return round(area, 4)


def calculate_hypotenuse(a: float, b: float) -> float:
    """Calculates the hypotenuse of a right-angled triangle given sides a and b.

    Args:
        a: Length of side a.
        b: Length of side b.
    """
    hypotenuse = math.sqrt(a ** 2 + b ** 2)
    return round(hypotenuse, 4)


def calculate_cylinder_volume(radius: float, height: float) -> float:
    """Calculates the volume of a cylinder given its base radius and height.

    Args:
        radius: The radius of the circular base.
        height: The height of the cylinder.
    """
    volume = math.pi * (radius ** 2) * height
    return round(volume, 4)


def get_city_temperature(city: str) -> Dict[str, Any]:
    """Retrieves current weather and temperature data for a given city.

    Args:
        city: Name of the city (e.g. 'Tokyo', 'Paris', 'New York').
    """
    # Simulated weather database
    city_data = {
        "tokyo": {"temperature_c": 22.5, "condition": "Sunny", "humidity": 55},
        "paris": {"temperature_c": 16.0, "condition": "Partly Cloudy", "humidity": 68},
        "new york": {"temperature_c": 19.2, "condition": "Clear", "humidity": 48},
        "london": {"temperature_c": 14.8, "condition": "Rainy", "humidity": 82},
        "sydney": {"temperature_c": 24.1, "condition": "Breezy", "humidity": 60},
    }
    key = city.lower().strip()
    return city_data.get(key, {"city": city, "temperature_c": 20.0, "condition": "Mild", "humidity": 50})


def calculate_line_item_subtotal(item_name: str, quantity: int, unit_price: float) -> Dict[str, Any]:
    """Calculates the total cost for an order item line.

    Args:
        item_name: Name of the item/product.
        quantity: Number of units purchased.
        unit_price: Price per unit in USD.
    """
    subtotal = quantity * unit_price
    return {
        "item_name": item_name,
        "quantity": quantity,
        "unit_price": unit_price,
        "subtotal": round(subtotal, 2),
    }


TOOL_REGISTRY = {
    "calculate_circle_area": calculate_circle_area,
    "calculate_hypotenuse": calculate_hypotenuse,
    "calculate_cylinder_volume": calculate_cylinder_volume,
    "get_city_temperature": get_city_temperature,
    "calculate_line_item_subtotal": calculate_line_item_subtotal,
}

TOOLS_LIST = list(TOOL_REGISTRY.values())


# ==============================================================================
# 2. Pretty Turn Logger
# ==============================================================================

class MultiToolPayloadLogger:
    """Helper to highlight parallel tool calls and serialize API payloads."""

    @staticmethod
    def log_turn_request(turn: int, contents: List[Any], model: str):
        print("\n" + "═" * 90)
        print(f"📤 [TURN {turn}] REQUEST PAYLOAD TO GEMINI ({model})")
        print("═" * 90)
        
        serialized = []
        for c in contents:
            if hasattr(c, "model_dump"):
                serialized.append(c.model_dump(exclude_none=True))
            else:
                serialized.append(str(c))
        
        print(json.dumps({"turn": turn, "contents": serialized}, indent=2, default=str))

    @staticmethod
    def log_turn_response(turn: int, response: Any):
        print("\n" + "═" * 90)
        print(f"📥 [TURN {turn}] RESPONSE PAYLOAD FROM GEMINI")
        print("═" * 90)
        
        if hasattr(response, "model_dump"):
            resp_dict = response.model_dump(exclude_none=True)
            print(json.dumps(resp_dict, indent=2, default=str))
        else:
            print(str(response))
            
        # Highlight parallel tool calls if any
        if hasattr(response, "function_calls") and response.function_calls:
            calls = response.function_calls
            print("\n" + "─" * 90)
            print(f"⚡ [PARALLEL TOOL CALLS DETECTED]: Gemini emitted {len(calls)} tool calls in this single turn!")
            for idx, fc in enumerate(calls, 1):
                print(f"   [{idx}] Function: {fc.name} | Arguments: {fc.args}")
            print("─" * 90)

    @staticmethod
    def log_execution(call_idx: int, tool_name: str, args: dict, result: Any):
        print(f"  ⚙️  Executing [{call_idx}] `{tool_name}(**{args})` -> Result: {result}")


# ==============================================================================
# 3. Parallel Tool Agent Loop
# ==============================================================================

def run_parallel_tool_agent(prompt: str, client: genai.Client, model: str = MODEL_ID, max_turns: int = 5) -> str:
    """Executes a prompt, logging turns where Gemini calls multiple tools in parallel."""
    print("\n" + "█" * 90)
    print(f"🤖 AGENT USER PROMPT: \"{prompt}\"")
    print("█" * 90)

    # Initial conversation history with user prompt
    contents = [
        types.Content(
            role="user",
            parts=[types.Part.from_text(text=prompt)]
        )
    ]

    config = types.GenerateContentConfig(
        tools=TOOLS_LIST,
        temperature=0.0,
        # Disable automatic calling to manually handle & inspect parallel tool execution
        automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True),
        system_instruction=(
            "You are an analytical assistant. When questions involve multiple calculations, "
            "metrics, or data points, execute all necessary tool calls in parallel within a single turn "
            "to maximize efficiency."
        )
    )

    turn = 1
    while turn <= max_turns:
        # 1. Log outgoing request payload
        MultiToolPayloadLogger.log_turn_request(turn=turn, contents=contents, model=model)

        # 2. Call Gemini
        response = client.models.generate_content(
            model=model,
            contents=contents,
            config=config
        )

        # 3. Log incoming response payload
        MultiToolPayloadLogger.log_turn_response(turn=turn, response=response)

        # 4. Check if final answer was produced
        if not response.function_calls:
            print("\n" + "🎉" * 45)
            print(f"🎯 [FINAL ANSWER RECEIVED IN {turn} TURN(S)]:")
            print(response.text)
            print("🎉" * 45)
            return response.text

        # Append model response containing the multiple function_calls to history
        contents.append(response.candidates[0].content)

        # 5. Execute all tool calls emitted in this single turn
        print(f"\n⚙️  [EXECUTING {len(response.function_calls)} TOOL CALLS LOCALLY]")
        tool_response_parts = []
        for idx, fc in enumerate(response.function_calls, 1):
            tool_name = fc.name
            tool_args = fc.args or {}

            if tool_name not in TOOL_REGISTRY:
                res = f"Error: Tool '{tool_name}' is not recognized."
            else:
                try:
                    res = TOOL_REGISTRY[tool_name](**tool_args)
                except Exception as e:
                    res = f"Error executing '{tool_name}': {str(e)}"

            MultiToolPayloadLogger.log_execution(idx, tool_name, tool_args, res)

            # Create a function_response part for each tool execution
            tool_response_parts.append(
                types.Part.from_function_response(
                    name=tool_name,
                    response={"result": res}
                )
            )

        # 6. Append ALL function responses together into a single user turn!
        # This sends all parallel results back to Gemini in ONE request.
        contents.append(
            types.Content(
                role="user",
                parts=tool_response_parts
            )
        )

        turn += 1

    print("⚠️ Max turns reached without completion.")
    return "Max turns reached."


# ==============================================================================
# 4. Mock Demonstration (Offline Mode)
# ==============================================================================

def run_mock_demo():
    """Simulates the exact payloads of a multi-tool turn without an active API key."""
    print("\n" + "🧪" * 45)
    print("RUNNING OFFLINE MOCK DEMO (SIMULATED MULTIPLE TOOL CALLS)")
    print("🧪" * 45)

    prompt = (
        "I need measurements for 3 shapes at once:\n"
        "1. Area of a circle with radius 7 meters\n"
        "2. Hypotenuse of a right triangle with sides 9 and 12 meters\n"
        "3. Volume of a cylinder with radius 4 meters and height 15 meters"
    )

    # 1. Turn 1 Request
    contents = [types.Content(role="user", parts=[types.Part.from_text(text=prompt)])]
    MultiToolPayloadLogger.log_turn_request(turn=1, contents=contents, model=MODEL_ID)

    # 2. Turn 1 Mock Response from Gemini (3 function calls in 1 turn!)
    fc1 = types.FunctionCall(name="calculate_circle_area", args={"radius": 7.0})
    fc2 = types.FunctionCall(name="calculate_hypotenuse", args={"a": 9.0, "b": 12.0})
    fc3 = types.FunctionCall(name="calculate_cylinder_volume", args={"radius": 4.0, "height": 15.0})

    mock_response = types.GenerateContentResponse(
        candidates=[
            types.Candidate(
                content=types.Content(
                    role="model",
                    parts=[types.Part(function_call=fc1), types.Part(function_call=fc2), types.Part(function_call=fc3)]
                ),
                finish_reason=types.FinishReason.STOP
            )
        ],
        usage_metadata=types.GenerateContentResponseUsageMetadata(
            prompt_token_count=180,
            candidates_token_count=65,
            total_token_count=245
        )
    )
    MultiToolPayloadLogger.log_turn_response(turn=1, response=mock_response)

    # 3. Agent executes all 3 tools
    contents.append(mock_response.candidates[0].content)
    print(f"\n⚙️  [EXECUTING {len(mock_response.function_calls)} TOOL CALLS LOCALLY]")
    tool_responses = []
    for idx, fc in enumerate(mock_response.function_calls, 1):
        res = TOOL_REGISTRY[fc.name](**fc.args)
        MultiToolPayloadLogger.log_execution(idx, fc.name, fc.args, res)
        tool_responses.append(types.Part.from_function_response(name=fc.name, response={"result": res}))

    # 4. Turn 2 Request (Packaging all 3 results in a single turn)
    contents.append(types.Content(role="user", parts=tool_responses))
    MultiToolPayloadLogger.log_turn_request(turn=2, contents=contents, model=MODEL_ID)

    # 5. Turn 2 Mock Synthesized Final Answer
    final_text = (
        "Here are the calculations for your three shapes:\n"
        "1. Circle Area: 153.938 m²\n"
        "2. Triangle Hypotenuse: 15.0 m\n"
        "3. Cylinder Volume: 753.9822 m³"
    )
    mock_final_response = types.GenerateContentResponse(
        candidates=[
            types.Candidate(
                content=types.Content(
                    role="model",
                    parts=[types.Part.from_text(text=final_text)]
                ),
                finish_reason=types.FinishReason.STOP
            )
        ],
        usage_metadata=types.GenerateContentResponseUsageMetadata(
            prompt_token_count=285,
            candidates_token_count=52,
            total_token_count=337
        )
    )
    MultiToolPayloadLogger.log_turn_response(turn=2, response=mock_final_response)
    print("\n" + "🎉" * 45)
    print("🎯 [FINAL SYNTHESIZED ANSWER]:")
    print(final_text)
    print("🎉" * 45)


# ==============================================================================
# 5. Main Entrypoint
# ==============================================================================

if __name__ == "__main__":
    # Check for --mock flag
    if "--mock" in sys.argv or "-m" in sys.argv:
        run_mock_demo()
        sys.exit(0)

    # Retrieve or prompt for API key
    api_key = os.environ.get("GEMINI_API_KEY")
    if not api_key:
        print("🔑 GEMINI_API_KEY not found in environment.")
        print("💡 You can run in offline mock mode anytime with: python multi_tool_turn_demo.py --mock")
        try:
            api_key = getpass.getpass("👉 Paste your Gemini API Key here (or press Enter to run mock): ").strip()
        except Exception:
            api_key = input("👉 Paste your Gemini API Key here: ").strip()

        if not api_key:
            print("No key entered. Running offline mock demonstration...")
            run_mock_demo()
            sys.exit(0)
        os.environ["GEMINI_API_KEY"] = api_key

    client = genai.Client(api_key=api_key)

    # Example 1: Parallel geometry calculations in a single turn
    prompt_demo_1 = (
        "Compute the following measurements in parallel:\n"
        "1. Area of a circle with radius 7\n"
        "2. Hypotenuse of a right triangle with legs 9 and 12\n"
        "3. Volume of a cylinder with radius 4 and height 15"
    )
    run_parallel_tool_agent(prompt_demo_1, client=client)

    # Example 2: Parallel weather lookup for multiple cities in a single turn
    prompt_demo_2 = (
        "Compare the weather right now in Tokyo, Paris, and London. "
        "Which city is currently the warmest?"
    )
    run_parallel_tool_agent(prompt_demo_2, client=client)
