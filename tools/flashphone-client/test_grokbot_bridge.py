import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock
from grokbot_bridge import dispatch, check_directory, ClientError


class GrokBotBridgeTest(unittest.TestCase):
    def test_uncertain_dial_is_never_retried(self):
        client = Mock()
        client.tool.side_effect = ClientError('uncertain')
        with self.assertRaises(ClientError):
            dispatch(client, {'operation': 'call', 'name': 'flashphone_call_task',
                'arguments': {'idempotency_key': 'one-intention'}})
        self.assertEqual(client.tool.call_count, 1)

    def test_general_execution_is_not_exposed(self):
        client = Mock()
        for name in ('exec', 'mika_run_task', 'flashphone_unknown'):
            with self.assertRaises(ClientError):
                dispatch(client, {'operation': 'call', 'name': name, 'arguments': {}})
        client.tool.assert_not_called()

    def test_final_result_is_not_reinterpreted(self):
        result = {'structuredContent': {'task': {'phase': 'ended', 'carrier_ended': True,
            'result': {'summary': 'actual outcome', 'transcript': [{'speaker': 'caller', 'text': 'answer'}]}}}}
        client = Mock()
        client.tool.return_value = result
        self.assertIs(dispatch(client, {'operation': 'call', 'name': 'flashphone_wait_task',
            'arguments': {'task_id': 'task', 'timeout_seconds': 25}}), result)

    def test_socket_directory_cannot_be_public_or_symlink(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            directory.chmod(0o700)
            check_directory(directory)
            link = directory / 'link'
            link.symlink_to(directory, target_is_directory=True)
            with self.assertRaises(ClientError):
                check_directory(link)
            directory.chmod(0o755)
            with self.assertRaises(ClientError):
                check_directory(directory)


if __name__ == '__main__':
    unittest.main()
